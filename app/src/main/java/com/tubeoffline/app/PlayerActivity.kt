package com.tubeoffline.app

import android.app.PictureInPictureParams
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerActivity : ComponentActivity() {

    var controller: MediaController? = null
        private set

    private var inPictureInPicture by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inPictureInPicture = isInPictureInPictureMode
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                PlayerScreen(
                    inPictureInPicture = inPictureInPicture,
                    onControllerReady = { controller = it },
                    onDismiss = {
                        Toast.makeText(
                            this,
                            "Playback continues. Tap Now playing to return.",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    },
                    onStop = {
                        controller?.stop()
                        controller?.clearMediaItems()
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = controller ?: return
        if (!c.isPlaying) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        enterPictureInPictureMode(buildPipParams())
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"

        fun playIntent(context: Context, uri: Uri, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_TITLE, title)

        fun playRequestFrom(intent: Intent): PlayRequest? {
            val uriString = intent.getStringExtra(EXTRA_URI) ?: return null
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
            return PlayRequest(Uri.parse(uriString), title)
        }
    }
}

data class PlayRequest(val uri: Uri, val title: String)

@UnstableApi
@Composable
private fun PlayerScreen(
    inPictureInPicture: Boolean,
    onControllerReady: (MediaController?) -> Unit,
    onDismiss: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as PlayerActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var playRequest by remember { mutableStateOf(PlayerActivity.playRequestFrom(activity.intent)) }
    var controllerVisible by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteYouTubeId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val showTitleRow = !inPictureInPicture && (!isLandscape || controllerVisible)

    fun cleanupDeleted(uri: Uri, youTubeId: String?) {
        PlaybackPositionStore.clear(context, uri)
        ThumbnailCache.delete(context, youTubeId)
    }

    fun finishAfterDelete() {
        controller?.let {
            runCatching {
                it.stop()
                it.clearMediaItems()
            }
        }
        activity.finish()
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteUri?.let { uri ->
                PlaybackService.stopIfPlaying(uri)
                cleanupDeleted(uri, pendingDeleteYouTubeId)
                pendingDeleteUri = null
                pendingDeleteYouTubeId = null
                finishAfterDelete()
                return@rememberLauncherForActivityResult
            }
        }
        pendingDeleteUri = null
        pendingDeleteYouTubeId = null
    }

    if (playRequest == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, "Could not open this video", Toast.LENGTH_SHORT).show()
            activity.finish()
        }
        return
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val next = PlayerActivity.playRequestFrom(activity.intent) ?: return@LifecycleEventEffect
        if (next != playRequest) playRequest = next
    }

    LaunchedEffect(Unit) {
        try {
            val token = SessionToken(
                context,
                android.content.ComponentName(context, PlaybackService::class.java),
            )
            val c = connectMediaController(context, token)
            controller = c
            onControllerReady(c)
        } catch (_: Exception) {
            activity.finish()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller?.let {
                savePlaybackProgress(context, it)
                it.release()
                onControllerReady(null)
            }
        }
    }

    LaunchedEffect(controller, playRequest) {
        val c = controller ?: return@LaunchedEffect
        val req = playRequest ?: return@LaunchedEffect
        val savedMs = PlaybackPositionStore.getPositionMs(context, req.uri)
        val startPaused = PlaybackPositionStore.wasPaused(context, req.uri)
        val mediaId = req.uri.toString()
        val alreadyLoaded = c.currentMediaItem?.mediaId == mediaId &&
            c.playbackState != Player.STATE_IDLE
        if (alreadyLoaded) {
            if (abs(c.currentPosition - savedMs) > 500) c.seekTo(savedMs)
            c.playWhenReady = !startPaused
        } else {
            c.setMediaItem(PlaybackService.mediaItem(req.uri, req.title), savedMs)
            c.prepare()
            c.playWhenReady = !startPaused
        }
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        playbackSpeed = c.playbackParameters.speed
        // Keep chrome title / playRequest in sync when autoplay advances the queue.
        c.currentMediaItem?.let { item ->
            val title = item.mediaMetadata.title?.toString() ?: playRequest?.title ?: "Video"
            val synced = PlayRequest(Uri.parse(item.mediaId), title)
            if (playRequest?.uri != synced.uri) playRequest = synced
        }
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                playbackSpeed = playbackParameters.speed
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val item = mediaItem ?: return
                val title = item.mediaMetadata.title?.toString() ?: "Video"
                playRequest = PlayRequest(Uri.parse(item.mediaId), title)
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    val currentController by rememberUpdatedState(controller)
    val currentPlayerView by rememberUpdatedState(playerView)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> currentPlayerView?.player = currentController
                Lifecycle.Event.ON_STOP -> {
                    currentPlayerView?.player = null
                    currentController?.let { savePlaybackProgress(context, it) }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    useController = !inPictureInPicture
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controllerVisible = visibility == View.VISIBLE
                        },
                    )
                    playerView = this
                    controller?.let { player = it }
                }
            },
            update = { view ->
                view.useController = !inPictureInPicture
                playerView = view
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    view.player = controller
                }
            },
        )

        if (showTitleRow) Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding(),
            // Overlay sits on black video; use inverse colors so title/icons stay light.
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.94f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            tonalElevation = 3.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = playRequest?.title ?: "Now playing",
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box {
                    TextButton(
                        onClick = { speedMenuOpen = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text(
                            formatPlaybackSpeed(playbackSpeed),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    DropdownMenu(
                        expanded = speedMenuOpen,
                        onDismissRequest = { speedMenuOpen = false },
                    ) {
                        PlaybackSpeeds.forEach { speed ->
                            val selected = abs(playbackSpeed - speed) < 0.01f
                            DropdownMenuItem(
                                text = { Text(formatPlaybackSpeed(speed)) },
                                leadingIcon = {
                                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                        if (selected) {
                                            Icon(Icons.Filled.Check, contentDescription = "Current speed")
                                        }
                                    }
                                },
                                onClick = {
                                    controller?.setPlaybackSpeed(speed)
                                    playbackSpeed = speed
                                    speedMenuOpen = false
                                },
                            )
                        }
                    }
                }
                if (isLandscape) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete video")
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                }
                IconButton(onClick = onStop) {
                    Icon(StopIcon, contentDescription = "Stop")
                }
            }
        }

        if (confirmDelete) {
            val title = playRequest?.title ?: "This video"
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete video?") },
                text = {
                    Text(
                        "$title\n\nThis permanently deletes the file from Movies/TubeOffline. It is not sent to trash.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        val current = playRequest ?: return@TextButton
                        scope.launch {
                            PlaybackService.stopIfPlaying(current.uri)
                            val youTubeId = withContext(Dispatchers.IO) {
                                runCatching {
                                    VideoStore.list(context).firstOrNull { it.uri == current.uri }?.youTubeId
                                }.getOrNull()
                            }
                            val result = withContext(Dispatchers.IO) {
                                VideoStore.delete(context, current.uri)
                            }
                            when (result) {
                                VideoStore.DeleteResult.Deleted -> {
                                    cleanupDeleted(current.uri, youTubeId)
                                    finishAfterDelete()
                                }
                                is VideoStore.DeleteResult.NeedsConfirmation -> {
                                    pendingDeleteUri = current.uri
                                    pendingDeleteYouTubeId = youTubeId
                                    deleteLauncher.launch(
                                        IntentSenderRequest.Builder(result.intentSender).build(),
                                    )
                                }
                                is VideoStore.DeleteResult.Failed -> {
                                    Toast.makeText(
                                        context,
                                        "Couldn't delete: ${result.message}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    }) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
                },
            )
        }
    }
}

private val PlaybackSpeeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private fun formatPlaybackSpeed(speed: Float): String {
    val snapped = PlaybackSpeeds.firstOrNull { abs(it - speed) < 0.01f } ?: speed
    val label = if (abs(snapped - snapped.toInt()) < 0.01f) {
        snapped.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", snapped).trimEnd('0')
    }
    return "${label}×"
}

private val StopIcon: ImageVector = ImageVector.Builder(
    name = "Stop",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(6f, 6f)
        lineTo(18f, 6f)
        lineTo(18f, 18f)
        lineTo(6f, 18f)
        close()
    }
}.build()

private fun savePlaybackProgress(context: Context, player: Player) {
    val uri = player.currentMediaItem?.mediaId?.let(Uri::parse) ?: return
    PlaybackPositionStore.record(
        context,
        uri,
        player.currentPosition,
        player.duration,
        paused = !player.isPlaying,
        ended = player.playbackState == Player.STATE_ENDED,
    )
}

private suspend fun connectMediaController(context: Context, token: SessionToken): MediaController =
    suspendCancellableCoroutine { cont ->
        val future: ListenableFuture<MediaController> =
            MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            },
            MoreExecutors.directExecutor(),
        )
        cont.invokeOnCancellation { MediaController.releaseFuture(future) }
    }
