package com.tubeoffline.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

data class DownloadStatus(
    val active: Boolean = false,
    val title: String = "",
    val percent: Int? = null,
    val queued: Int = 0,
    /** YouTube id for the active download; drives the Downloading now thumbnail. */
    val youTubeId: String? = null,
    /** Bumped every time a download finishes (success or failure) so the UI can refresh. */
    val finishedCount: Int = 0,
    val lastMessage: String? = null,
)

/**
 * Foreground service that downloads queued YouTube URLs one after another with yt-dlp.
 * Started from [ShareActivity] (while it is visible, so Android allows the FGS start).
 */
class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_DOWNLOAD = "com.tubeoffline.app.DOWNLOAD"
        const val ACTION_CANCEL = "com.tubeoffline.app.CANCEL"
        const val EXTRA_URL = "url"

        /** Initial attempt + up to this many automatic safer-format retries. */
        private const val MAX_FORMAT_RETRIES = 2

        private val _status = MutableStateFlow(DownloadStatus())
        val status: StateFlow<DownloadStatus> = _status.asStateFlow()

        fun enqueue(context: Context, url: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java).setAction(ACTION_DOWNLOAD).putExtra(EXTRA_URL, url),
            )
        }
    }

    private data class FormatAttempt(
        val format: String,
        val remuxToMp4: Boolean,
        val label: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val queue = ArrayDeque<String>()
    private var worker: Job? = null
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentProcessId: String? = null
    @Volatile private var cancelRequested = false
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always satisfy the startForegroundService() contract first.
        goForeground("Preparing download…", null)

        when (intent?.action) {
            ACTION_CANCEL -> {
                // Cancel the current download and everything queued behind it.
                cancelRequested = true
                synchronized(lock) {
                    queue.clear()
                    currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
                    if (worker == null) stopNow()
                }
            }
            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                synchronized(lock) {
                    if (url != null && url != currentUrl && url !in queue) queue.addLast(url)
                    publish { it.copy(queued = queue.size) }
                    if (worker == null) worker = scope.launch { runQueue() }
                }
            }
            else -> synchronized(lock) { if (worker == null) stopNow() }
        }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        while (true) {
            val url = synchronized(lock) {
                val next = queue.pollFirst()
                if (next == null) {
                    worker = null
                    currentUrl = null
                    stopNow()
                    return
                }
                currentUrl = next
                val id = YouTubeUrl.videoId(next)
                publish { it.copy(active = true, title = "Starting…", percent = null, queued = queue.size, youTubeId = id) }
                next
            }
            // Warm thumbnail cache off the queue lock (network).
            YouTubeUrl.videoId(url)?.let { ThumbnailCache.ensureCached(this, it) }
            cancelRequested = false
            download(url)
        }
    }

    private fun download(url: String) {
        val attempts = listOf(
            FormatAttempt(Engine.FORMAT_LOWEST, remuxToMp4 = true, label = "lowest"),
            FormatAttempt(Engine.FORMAT_SAFE_MP4, remuxToMp4 = true, label = "safe mp4"),
            FormatAttempt(Engine.FORMAT_SAFE_MP4, remuxToMp4 = true, label = "safe mp4 retry"),
        ).take(1 + MAX_FORMAT_RETRIES)

        var lastError: Throwable? = null
        try {
            for ((index, attempt) in attempts.withIndex()) {
                if (cancelRequested) {
                    publish { it.copy(lastMessage = "Cancelled") }
                    return
                }
                val workDir = File(cacheDir, "downloads/${System.currentTimeMillis()}-$index").apply { mkdirs() }
                try {
                    if (index > 0) {
                        Log.w(TAG, "Retry $index/${MAX_FORMAT_RETRIES} with ${attempt.label} after: ${lastError?.message}")
                        goForeground("Retrying with a safer format…", null)
                        publish { it.copy(title = "Retrying with a safer format…", percent = null) }
                    }
                    downloadOnce(url, workDir, attempt)
                    return
                } catch (e: YoutubeDL.CanceledException) {
                    publish { it.copy(lastMessage = "Cancelled") }
                    return
                } catch (e: InterruptedException) {
                    publish { it.copy(lastMessage = "Cancelled") }
                    return
                } catch (e: YoutubeDLException) {
                    if (cancelRequested) {
                        publish { it.copy(lastMessage = "Cancelled") }
                        return
                    }
                    lastError = e
                    if (!Engine.isRetryableFormatOrMimeError(e) || index == attempts.lastIndex) {
                        Log.e(TAG, "yt-dlp failed", e)
                        val msg = Engine.readableError(e.message)
                        Notifications.failure(this, url, msg)
                        publish { it.copy(lastMessage = "Failed: $msg") }
                        return
                    }
                } catch (e: Throwable) {
                    if (cancelRequested) {
                        publish { it.copy(lastMessage = "Cancelled") }
                        return
                    }
                    lastError = e
                    if (!Engine.isRetryableFormatOrMimeError(e) || index == attempts.lastIndex) {
                        Log.e(TAG, "Download failed", e)
                        val msg = if (e is VideoStore.UnsupportedMimeException) {
                            "Couldn't save this video format to the phone library."
                        } else {
                            e.message ?: e.javaClass.simpleName
                        }
                        Notifications.failure(this, url, msg)
                        publish { it.copy(lastMessage = "Failed: $msg") }
                        return
                    }
                } finally {
                    currentProcessId = null
                    workDir.deleteRecursively()
                }
            }
        } finally {
            publish { it.copy(active = false, percent = null, youTubeId = null, finishedCount = it.finishedCount + 1) }
        }
    }

    private fun downloadOnce(url: String, workDir: File, attempt: FormatAttempt) {
        val processId = "dl-${System.nanoTime()}"
        currentProcessId = processId
        var title = YouTubeUrl.videoId(url) ?: url
        goForeground(title, null)
        goForeground("Starting download engine…", null)
        Engine.refreshIfStale(this) {
            goForeground("Updating download engine…", null)
        }

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-update")
            addOption("-f", attempt.format)
            addOption("--merge-output-format", "mp4")
            if (attempt.remuxToMp4) {
                // Remux webm/mkv merges into mp4 so MediaStore.Video accepts the file.
                addOption("--remux-video", "mp4")
            }
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--cache-dir", File(cacheDir, "yt-dlp-cache").absolutePath)
            addOption("--write-info-json")
            addOption("-o", File(workDir, "%(title).150B.%(ext)s").absolutePath)
        }

        YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
            // "[download] Destination: /…/My title.f160.mp4" -> "My title"
            if (line.contains("Destination:")) {
                title = File(line.substringAfter("Destination:").trim()).name
                    .replace(Regex("""(\.f\d+[^.]*)?\.[A-Za-z0-9]+$"""), "")
            }
            val pct = progress.toInt().takeIf { it in 0..100 }
            val merging = line.contains("[Merger]") || line.contains("[Remux]")
            publish { it.copy(title = title, percent = if (merging) null else pct) }
            maybeNotify(
                when {
                    line.contains("[Remux]") -> "Converting to mp4…"
                    merging -> "Merging audio + video…"
                    else -> title
                },
                if (merging) null else pct,
            )
        }

        val output = workDir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.filterNot { it.name.endsWith(".info.json") }
            ?.filterNot { Regex("""\.f\d+[^.]*\.[A-Za-z0-9]+$""").containsMatchIn(it.name) } // leftover unmerged parts
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("yt-dlp finished but produced no video file")

        title = output.nameWithoutExtension
        goForeground("Saving to Movies/TubeOffline…", null)
        val youTubeId = YouTubeUrl.videoId(url)
        val uploadedEpochSec = readUploadEpoch(workDir)
        val uri = VideoStore.save(this, output, title, youTubeId, uploadedEpochSec)
        youTubeId?.let { id -> ThumbnailCache.ensureCached(this, id) }
        Notifications.success(this, VideoStore.sanitizeFileName(title), uri, output.length())
        publish { it.copy(lastMessage = "Saved: $title") }
    }

    /** YouTube upload time from the info JSON yt-dlp already wrote for this download. */
    private fun readUploadEpoch(workDir: File): Long? {
        val info = workDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".info.json") }
            ?: return null
        return try {
            val json = JSONObject(info.readText())
            UploadLabel.epochFromYoutube(
                timestamp = json.optLong("timestamp", 0L),
                releaseTimestamp = json.optLong("release_timestamp", 0L),
                uploadDate = json.optString("upload_date", "").takeIf { it.isNotEmpty() },
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Could not read upload date", e)
            null
        }
    }

    private fun maybeNotify(text: String, percent: Int?) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < 700) return
        lastNotifyAt = now
        goForeground(text, percent)
    }

    private fun goForeground(text: String, percent: Int?) {
        val queued = synchronized(lock) { queue.size }
        val header = if (queued > 0) "Downloading (+$queued queued)" else "Downloading (lowest quality)"
        val notification = Notifications.progress(this, header, text, percent)
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException; just update the notification instead.
            Log.w(TAG, "startForeground failed", e)
            if (Notifications.canPost(this)) {
                try { NotificationManagerCompat.from(this).notify(Notifications.ID_PROGRESS, notification) } catch (_: SecurityException) {}
            }
        }
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private inline fun publish(update: (DownloadStatus) -> DownloadStatus) {
        _status.value = update(_status.value)
    }

    /** Android 15+: dataSync services get ~6h/day. Stop cleanly if we ever hit that. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelRequested = true
        currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        synchronized(lock) { queue.clear() }
        stopNow()
    }

    override fun onDestroy() {
        currentProcessId?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        scope.cancel()
        super.onDestroy()
    }
}
