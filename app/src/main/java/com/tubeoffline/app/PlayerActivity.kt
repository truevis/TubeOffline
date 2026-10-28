package com.tubeoffline.app

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
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

class PlayerActivity : ComponentActivity() {

    var controller: MediaController? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                PlayerScreen(
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

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    playerView = this
                    controller?.let { player = it }
                }
            },
            update = { view ->
                playerView = view
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    view.player = controller
                }
            },
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
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
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

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
