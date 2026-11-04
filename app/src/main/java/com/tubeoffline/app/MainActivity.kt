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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class MainActivity : ComponentActivity() {
    private val highlightUriState = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        highlightUriState.value = highlightUriFrom(intent)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val highlightUri by highlightUriState
                MainScreen(
                    highlightUri = highlightUri,
                    onHighlightConsumed = { highlightUriState.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        highlightUriState.value = highlightUriFrom(intent)
    }

    companion object {
        const val EXTRA_HIGHLIGHT_URI = "highlight_uri"

        fun openLibraryIntent(context: android.content.Context, highlightUri: Uri? = null): Intent =
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                highlightUri?.let { putExtra(EXTRA_HIGHLIGHT_URI, it.toString()) }
            }

        fun highlightUriFrom(intent: Intent?): Uri? =
            intent?.getStringExtra(EXTRA_HIGHLIGHT_URI)?.let(Uri::parse)
    }
}

/** Swipe-to-trash awaiting Snackbar Undo (or activity stop). */
private data class PendingSwipeTrash(
    val video: SavedVideo,
    val insertIndex: Int,
    /** True when MediaStore IS_TRASHED was set; false means permanent delete is still pending. */
    val mediaStoreTrashed: Boolean,
)

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
private fun MainScreen(
    highlightUri: Uri? = null,
    onHighlightConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by DownloadService.status.collectAsStateWithLifecycle()
    val nowPlaying by PlaybackService.nowPlaying.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var videos by remember { mutableStateOf<List<SavedVideo>>(emptyList()) }
    var completedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<SavedVideo?>(null) }
    var pendingDeleteUri by remember { mutableStateOf<Uri?>(null) }
    var pendingDeleteYouTubeId by remember { mutableStateOf<String?>(null) }
    var pendingSwipeTrash by remember {
        mutableStateOf<Map<String, PendingSwipeTrash>>(emptyMap())
    }
    val snackbarHostState = remember { SnackbarHostState() }
    var pasted by remember { mutableStateOf("") }
    var searchQuery by remember {
        mutableStateOf(AppPreferences.getLibrarySearchQuery(context))
    }
    val haptic = LocalHapticFeedback.current
    val videosLatest = rememberUpdatedState(videos)
    val searchQueryLatest = rememberUpdatedState(searchQuery)
    // Persist search so PlaybackService (autoplay / notification prev-next) stays
    // scoped while the UI is backgrounded, in PiP, or after process death.
    LaunchedEffect(searchQuery) {
        AppPreferences.setLibrarySearchQuery(context, searchQuery)
    }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyListState
        val toKey = to.key as? String ?: return@rememberReorderableLazyListState
        val q = searchQueryLatest.value.trim()
        val full = videosLatest.value
        if (q.isEmpty()) {
            val mutable = full.toMutableList()
            val fromIndex = mutable.indexOfFirst { it.uri.toString() == fromKey }
            val toIndex = mutable.indexOfFirst { it.uri.toString() == toKey }
            if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
                return@rememberReorderableLazyListState
            }
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            videos = mutable
            LibraryOrderStore.saveOrder(context, mutable.map { it.uri })
        } else {
            // Reorder only among visible matches; hidden rows keep their slots.
            val visible = full.filter { VideoStore.matchesSearch(it, q) }.toMutableList()
            val fromIndex = visible.indexOfFirst { it.uri.toString() == fromKey }
            val toIndex = visible.indexOfFirst { it.uri.toString() == toKey }
            if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) {
                return@rememberReorderableLazyListState
            }
            val item = visible.removeAt(fromIndex)
            visible.add(toIndex, item)
            val fullKeys = full.map { it.uri.toString() }
            val visibleKeys = visible.map { it.uri.toString() }
            val mergedKeys = mergeFilteredReorder(fullKeys, visibleKeys)
            val byKey = full.associateBy { it.uri.toString() }
            val merged = mergedKeys.mapNotNull { byKey[it] }
            videos = merged
            LibraryOrderStore.saveOrder(context, merged.map { it.uri })
        }
        haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    var pendingHighlightUri by remember { mutableStateOf<Uri?>(null) }
    val activeHighlightUri = pendingHighlightUri ?: highlightUri
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    var autoplay by remember { mutableStateOf(AppPreferences.isAutoplayEnabled(context)) }

    val filteredVideos = remember(videos, searchQuery) {
        videos.filter { VideoStore.matchesSearch(it, searchQuery) }
    }

    // When opened from share (or duplicate paste), scroll the existing row into view briefly.
    LaunchedEffect(activeHighlightUri, videos, filteredVideos, status.active, status.lastMessage, nowPlaying) {
        val target = activeHighlightUri ?: return@LaunchedEffect
        if (searchQuery.isNotBlank()) {
            searchQuery = ""
            return@LaunchedEffect // recompose with full list, then scroll
        }
        val indexInLib = filteredVideos.indexOfFirst { it.uri == target }
        if (indexInLib < 0) return@LaunchedEffect
        var headerCount = 2 // sponsor + paste
        if (nowPlaying != null) headerCount++
        if (status.active) headerCount++
        else if (status.lastMessage != null) headerCount++
        headerCount++ // library heading placeholder item
        if (videos.isNotEmpty()) headerCount++ // search field
        delay(50)
        runCatching { listState.animateScrollToItem(headerCount + indexInLib) }
        delay(2500)
        if (pendingHighlightUri == target) pendingHighlightUri = null
        if (highlightUri == target) onHighlightConsumed()
    }

    val refresh = { refreshKey++ }

    fun cleanupDeleted(uri: Uri, youTubeId: String?) {
        PlaybackPositionStore.clear(context, uri)
        LibraryOrderStore.remove(context, uri)
        ThumbnailCache.delete(context, youTubeId)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingDeleteUri?.let { uri ->
                PlaybackService.stopIfPlaying(uri)
                cleanupDeleted(uri, pendingDeleteYouTubeId)
            }
        }
        pendingDeleteUri = null
        pendingDeleteYouTubeId = null
        refresh()
    }

    fun restoreSwipeRow(pending: PendingSwipeTrash) {
        val key = pending.video.uri.toString()
        val mutable = videos.toMutableList()
        if (mutable.none { it.uri.toString() == key }) {
            val index = pending.insertIndex.coerceIn(0, mutable.size)
            mutable.add(index, pending.video)
            videos = mutable
        }
        if (PlaybackPositionStore.isCompleted(context, pending.video.uri)) {
            completedUris = completedUris + key
        }
    }

    fun commitSwipeTrash(key: String) {
        val pending = pendingSwipeTrash[key] ?: return
        pendingSwipeTrash = pendingSwipeTrash - key
        scope.launch {
            PlaybackService.stopIfPlaying(pending.video.uri)
            if (pending.mediaStoreTrashed) {
                cleanupDeleted(pending.video.uri, pending.video.youTubeId)
            } else {
                val result = withContext(Dispatchers.IO) {
                    VideoStore.delete(context, pending.video.uri)
                }
                when (result) {
                    VideoStore.DeleteResult.Deleted -> {
                        cleanupDeleted(pending.video.uri, pending.video.youTubeId)
                    }
                    is VideoStore.DeleteResult.NeedsConfirmation -> {
                        // Swipe path must not prompt; leave the optimistic hide and ask via
                        // the existing delete-confirm launcher as a last resort.
                        pendingDeleteUri = pending.video.uri
                        pendingDeleteYouTubeId = pending.video.youTubeId
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
                        restoreSwipeRow(pending)
                    }
                }
            }
            refresh()
        }
    }

    fun undoSwipeTrash(key: String) {
        val pending = pendingSwipeTrash[key] ?: return
        pendingSwipeTrash = pendingSwipeTrash - key
        scope.launch {
            if (pending.mediaStoreTrashed) {
                val ok = withContext(Dispatchers.IO) {
                    VideoStore.untrash(context, pending.video.uri)
                }
                if (!ok) {
                    Toast.makeText(context, "Couldn't restore from trash", Toast.LENGTH_SHORT).show()
                    // Keep it out of the list; treat as committed soft-trash.
                    cleanupDeleted(pending.video.uri, pending.video.youTubeId)
                    refresh()
                    return@launch
                }
            }
            restoreSwipeRow(pending)
            refresh()
        }
    }

    fun swipeTrash(video: SavedVideo) {
        val key = video.uri.toString()
        if (key in pendingSwipeTrash) return
        val index = videos.indexOfFirst { it.uri.toString() == key }
        if (index < 0) return
        videos = videos.filter { it.uri.toString() != key }
        completedUris = completedUris - key
        PlaybackService.stopIfPlaying(video.uri)
        scope.launch {
            val trashResult = withContext(Dispatchers.IO) {
                VideoStore.trash(context, video.uri)
            }
            when (trashResult) {
                is VideoStore.TrashResult.Failed -> {
                    val mutable = videos.toMutableList()
                    mutable.add(index.coerceIn(0, mutable.size), video)
                    videos = mutable
                    Toast.makeText(
                        context,
                        "Couldn't move to trash: ${trashResult.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@launch
                }
                VideoStore.TrashResult.Trashed -> {
                    pendingSwipeTrash = pendingSwipeTrash + (
                        key to PendingSwipeTrash(video, index, mediaStoreTrashed = true)
                    )
                }
                VideoStore.TrashResult.Unsupported -> {
                    pendingSwipeTrash = pendingSwipeTrash + (
                        key to PendingSwipeTrash(video, index, mediaStoreTrashed = false)
                    )
                }
            }
            val result = snackbarHostState.showSnackbar(
                message = "Moved to trash",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )
            when (result) {
                SnackbarResult.ActionPerformed -> undoSwipeTrash(key)
                SnackbarResult.Dismissed -> commitSwipeTrash(key)
            }
        }
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        autoplay = AppPreferences.isAutoplayEnabled(context)
        refresh()
    }
    // Finalize any swipe-trash still awaiting Undo when leaving the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        val keys = pendingSwipeTrash.keys.toList()
        for (key in keys) commitSwipeTrash(key)
    }
    LaunchedEffect(refreshKey, status.finishedCount) {
        val listed = withContext(Dispatchers.IO) {
            try { VideoStore.list(context) } catch (_: Exception) { emptyList() }
        }
        val hideKeys = pendingSwipeTrash
            .filterValues { !it.mediaStoreTrashed }
            .keys
        videos = listed.filter { it.uri.toString() !in hideKeys }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("TubeOffline") },
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Text(
                            "Autoplay",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Switch(
                            checked = autoplay,
                            onCheckedChange = { enabled ->
                                autoplay = enabled
                                AppPreferences.setAutoplayEnabled(context, enabled)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
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
                            when (val outcome = DownloadService.enqueue(context, url)) {
                                is EnqueueOutcome.AlreadyDownloaded -> {
                                    Toast.makeText(context, "Already downloaded", Toast.LENGTH_SHORT).show()
                                    pasted = ""
                                    searchQuery = ""
                                    pendingHighlightUri = outcome.video.uri
                                }
                                EnqueueOutcome.AlreadyInFlight -> {
                                    Toast.makeText(context, "Already downloading", Toast.LENGTH_SHORT).show()
                                    pasted = ""
                                }
                                EnqueueOutcome.Queued -> {
                                    pasted = ""
                                    Toast.makeText(context, "Added to the download queue", Toast.LENGTH_SHORT).show()
                                }
                            }
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
                            Spacer(Modifier.padding(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                YouTubeThumbnail(status.youTubeId)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        status.title,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    Spacer(Modifier.padding(4.dp))
                                    val pct = status.percent
                                    if (pct == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (status.queued > 0) {
                                        Text(
                                            "${status.queued} more waiting in the queue",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
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
                val openVideo = {
                    val title = video.name.substringBeforeLast('.')
                    context.startActivity(
                        PlayerActivity.playIntent(context, video.uri, title, playImmediately = true),
                    )
                }
                val shareVideo = video.youTubeId?.let { id ->
                    {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/watch?v=$id")
                        context.startActivity(Intent.createChooser(send, null))
                    }
                }
                ReorderableItem(reorderableState, key = video.uri.toString()) { isDragging ->
                    val itemScope = this
                    SwipeTrashRow(onSwiped = { swipeTrash(video) }) {
                        VideoRow(
                            video = video,
                            played = video.uri.toString() in completedUris,
                            highlighted = video.uri == activeHighlightUri,
                            dragging = isDragging,
                            reorderScope = itemScope,
                            onOpen = openVideo,
                            onShare = shareVideo,
                            onDelete = { toDelete = video },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { video ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete video?") },
            text = {
                Text("${video.name}\n\nThis permanently deletes the file from Movies/TubeOffline. It is not sent to trash.")
            },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    videos = videos.filter { it.uri != video.uri }
                    completedUris = completedUris - video.uri.toString()
                    scope.launch {
                        PlaybackService.stopIfPlaying(video.uri)
                        val result = withContext(Dispatchers.IO) {
                            VideoStore.delete(context, video.uri)
                        }
                        when (result) {
                            VideoStore.DeleteResult.Deleted -> {
                                cleanupDeleted(video.uri, video.youTubeId)
                                refresh()
                            }
                            is VideoStore.DeleteResult.NeedsConfirmation -> {
                                pendingDeleteUri = video.uri
                                pendingDeleteYouTubeId = video.youTubeId
                                deleteLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                            }
                            is VideoStore.DeleteResult.Failed -> {
                                Toast.makeText(
                                    context,
                                    "Couldn't delete: ${result.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                                refresh()
                            }
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeTrashRow(
    onSwiped: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onSwiped()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(LibraryVideoCardShape)
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
    ) {
        Box(Modifier.clip(LibraryVideoCardShape)) {
            content()
        }
    }
}

private val LibraryVideoCardShape = RoundedCornerShape(12.dp)

@Composable
private fun VideoRow(
    video: SavedVideo,
    played: Boolean,
    highlighted: Boolean = false,
    dragging: Boolean = false,
    reorderScope: ReorderableCollectionItemScope? = null,
    onOpen: () -> Unit,
    onShare: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val elevation by animateDpAsState(if (dragging) 6.dp else 0.dp, label = "reorderElevation")
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = LibraryVideoCardShape,
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        border = if (highlighted) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
    ) {
        Row(
            Modifier.padding(start = 4.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (reorderScope != null) {
                IconButton(
                    modifier = with(reorderScope) { Modifier.draggableHandle() },
                    onClick = {},
                ) {
                    Icon(Icons.Filled.Menu, contentDescription = "Reorder")
                }
            }
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
                    video.uploadedEpochSec?.let { uploaded ->
                        add(UploadLabel.relative(uploaded, System.currentTimeMillis() / 1000))
                    }
                }
                Text(parts.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
            }
            if (onShare != null) {
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }
}
