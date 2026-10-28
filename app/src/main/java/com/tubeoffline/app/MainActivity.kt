package com.tubeoffline.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
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
    val scope = rememberCoroutineScope()
    val status by DownloadService.status.collectAsStateWithLifecycle()

    var videos by remember { mutableStateOf<List<SavedVideo>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var updating by remember { mutableStateOf(false) }
    var engineInfo by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<SavedVideo?>(null) }
    var pasted by remember { mutableStateOf("") }

    val refresh = { refreshKey++ }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { refresh() }

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
        engineInfo = withContext(Dispatchers.IO) { Engine.version(context)?.let { "yt-dlp $it" } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    LaunchedEffect(refreshKey, status.finishedCount) {
        videos = withContext(Dispatchers.IO) {
            try { VideoStore.list(context) } catch (_: Exception) { emptyList() }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("TubeOffline") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Share a YouTube video to “TubeOffline” from the YouTube app or Chrome. " +
                        "It downloads the smallest version, with the English audio, to Movies/TubeOffline.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("…or paste a YouTube link") },
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val url = YouTubeUrl.extract(pasted)
                        if (url == null) {
                            Toast.makeText(context, "That doesn't look like a YouTube video link", Toast.LENGTH_SHORT).show()
                        } else {
                            DownloadService.enqueue(context, url)
                            pasted = ""
                        }
                    }) { Text("Get") }
                }
            }

            if (status.active) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Downloading", style = MaterialTheme.typography.titleSmall)
                            Text(status.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.padding(4.dp))
                            val pct = status.percent
                            if (pct == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                            if (status.queued > 0) Text("${status.queued} more queued", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                context.startService(
                                    Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL)
                                )
                            }) { Text("Cancel") }
                        }
                    }
                }
            } else status.lastMessage?.let { msg ->
                item { Text(msg, style = MaterialTheme.typography.bodySmall) }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !updating,
                        onClick = {
                            updating = true
                            scope.launch {
                                val msg = withContext(Dispatchers.IO) {
                                    try { Engine.update(context) } catch (e: Exception) {
                                        "Update failed: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                                engineInfo = Engine.version(context)?.let { "yt-dlp $it" }
                                updating = false
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        },
                    ) { Text("Update download engine") }
                    Spacer(Modifier.width(12.dp))
                    if (updating) CircularProgressIndicator(Modifier.width(24.dp))
                    else engineInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            item {
                HorizontalDivider()
                Text(
                    if (videos.isEmpty()) "No videos downloaded yet." else "Downloaded (${videos.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            items(videos, key = { it.uri.toString() }) { video ->
                VideoRow(
                    video = video,
                    onOpen = {
                        val mime = VideoStore.mimeFor(video.name.substringAfterLast('.', "mp4"))
                        try {
                            context.startActivity(VideoStore.viewIntent(context, video.uri, mime))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "No video player installed", Toast.LENGTH_SHORT).show()
                        }
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
                        VideoStore.DeleteResult.Deleted -> refresh()
                        is VideoStore.DeleteResult.NeedsConfirmation ->
                            deleteLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build())
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
private fun VideoRow(video: SavedVideo, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(video.name.substringBeforeLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = buildList {
                    add(formatSize(video.sizeBytes))
                    if (video.durationMs > 0) add(DateUtils.formatElapsedTime(video.durationMs / 1000))
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
