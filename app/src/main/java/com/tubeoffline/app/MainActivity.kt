package com.tubeoffline.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
    }
}

@Composable
internal fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val status by DownloadService.status.collectAsStateWithLifecycle()
    val nowPlaying by PlaybackService.nowPlaying.collectAsStateWithLifecycle()

    var videos by remember { mutableStateOf<List<SavedVideo>>(emptyList()) }
    var completedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<SavedVideo?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pasted by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var freeBytes by remember { mutableStateOf<Long?>(null) }

    val filteredVideos = remember(videos, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) videos else videos.filter { it.name.contains(q, ignoreCase = true) }
    }

    val refresh = { refreshKey++ }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteUri?.let { PlaybackPositionStore.clear(context, it) }
        }
        pendingDeleteUri = null
        refresh()
    }

    LaunchedEffect(Unit) {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(refreshKey, status.finishedCount) {
        val listed = withContext(Dispatchers.IO) {
            try { VideoStore.list(context) } catch (_: Exception) { emptyList() }
        }
        videos = listed
        completedUris = listed
            .filter { PlaybackPositionStore.isCompleted(context, it.uri) }
            .map { it.uri.toString() }
            .toSet()
        freeBytes = withContext(Dispatchers.IO) {
            try {
                StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            } catch (_: Exception) {
                null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("TubeOffline")
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                val uriHandler = LocalUriHandler.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Sponsored by aifab.xyz",
                        modifier = Modifier.clickable { uriHandler.openUri("https://aifab.xyz") },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    )
                    freeBytes?.let { bytes ->
                        Text(
                            text = "${Formatter.formatFileSize(context, bytes)} free",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Paste a YouTube link") },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val url = YouTubeUrl.extract(pasted)
                        if (url == null) {
                            Toast.makeText(context, "That doesn't look like a YouTube video link", Toast.LENGTH_SHORT).show()
                        } else {
                            DownloadService.enqueue(context, url)
                            pasted = ""
                            Toast.makeText(context, "Added to the download queue", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Download") }
                }
            }

            nowPlaying?.let { playing ->
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(
                                    PlayerActivity.playIntent(context, playing.uri, playing.title),
                                )
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Now playing", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    playing.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }
                            Text(if (playing.isPlaying) "Open player" else "Resume")
                        }
                    }
                }
            }

            if (status.active) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Downloading now", style = MaterialTheme.typography.titleSmall)
                            Text(status.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.padding(4.dp))
                            val pct = status.percent
                            if (pct == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                            if (status.queued > 0) {
                                Text(
                                    "${status.queued} more waiting in the queue",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = {
                                context.startService(
                                    Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL)
                                )
                            }) { Text("Cancel download") }
                        }
                    }
                }
            } else status.lastMessage?.let { msg ->
                item { Text(msg, style = MaterialTheme.typography.bodySmall) }
            }

            item {
                HorizontalDivider()
                val q = searchQuery.trim()
                val heading = when {
                    videos.isEmpty() -> "Your library is empty"
                    q.isNotEmpty() -> "Results"
                    else -> null
                }
                if (heading != null) {
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (videos.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Search downloads") },
                    )
                }
            }

            if (videos.isNotEmpty() && searchQuery.isNotBlank() && filteredVideos.isEmpty()) {
                item {
                    Text("No downloads match that search.", style = MaterialTheme.typography.bodySmall)
                }
            }

            items(filteredVideos, key = { it.uri.toString() }) { video ->
                VideoRow(
                    video = video,
                    played = video.uri.toString() in completedUris,
                    onOpen = {
                        val title = video.name.substringBeforeLast('.')
                        context.startActivity(PlayerActivity.playIntent(context, video.uri, title))
                    },
                    onDelete = { toDelete = video },
                )
            }
        }
    }

    toDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete video?") },
            text = { Text(video.name) },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    when (val r = VideoStore.delete(context, video.uri)) {
                        VideoStore.DeleteResult.Deleted -> {
                            PlaybackPositionStore.clear(context, video.uri)
                            refresh()
                        }
                        is VideoStore.DeleteResult.NeedsConfirmation -> {
                            pendingDeleteUri = video.uri
                            deleteLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build())
                        }
                        is VideoStore.DeleteResult.Failed ->
                            Toast.makeText(context, "Couldn't delete: ${r.message}", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun VideoRow(video: SavedVideo, played: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            YouTubeThumbnail(video.youTubeId)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.name.substringBeforeLast('.'),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (played) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                val parts = buildList {
                    add(formatSize(video.sizeBytes))
                    if (video.durationMs > 0) add(DateUtils.formatElapsedTime(video.durationMs / 1000))
                }
                Text(parts.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
