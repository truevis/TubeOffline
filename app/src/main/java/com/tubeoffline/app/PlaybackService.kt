package com.tubeoffline.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Hosts ExoPlayer for background and screen-off playback with a media notification. */
@UnstableApi
class PlaybackService : MediaSessionService(), MediaSession.Callback {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this))
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(15_000)
            .build()
        player.addListener(PlaybackProgressListener(this, player) { mediaSession })
        val openPlayer = PendingIntent.getActivity(
            this,
            0,
            Intent(this, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setCallback(this)
            .build()
        activePlayer = player
        activeSession = mediaSession
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val resolved = mediaItems.map { item -> resolveMediaItem(item) }.toMutableList()
        return Futures.immediateFuture(resolved)
    }

    private fun resolveMediaItem(item: MediaItem): MediaItem {
        val uri = Uri.parse(item.mediaId)
        val ext = uri.lastPathSegment?.substringAfterLast('.', "") ?: "mp4"
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(VideoStore.mimeFor(ext))
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        _nowPlaying.value = null
        if (activePlayer === mediaSession?.player) activePlayer = null
        if (activeSession === mediaSession) activeSession = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private class PlaybackProgressListener(
        private val context: android.content.Context,
        private val player: Player,
        private val sessionProvider: () -> MediaSession?,
    ) : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateNowPlaying(player)
            val uri = player.currentMediaItem?.mediaId?.let(Uri::parse) ?: return
            if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
            PlaybackPositionStore.record(
                context,
                uri,
                player.currentPosition,
                player.duration,
                paused = !isPlaying,
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateNowPlaying(player)
            val item = player.currentMediaItem ?: return
            val uri = Uri.parse(item.mediaId)
            when (playbackState) {
                Player.STATE_ENDED -> {
                    PlaybackPositionStore.markCompleted(context, uri)
                    maybeAutoplayNext(context, player, uri)
                }
                Player.STATE_READY -> PlaybackPositionStore.record(
                    context,
                    uri,
                    player.currentPosition,
                    player.duration,
                    paused = !player.isPlaying,
                )
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateNowPlaying(player)
            mediaItem?.let { refreshSessionActivity(context, sessionProvider(), it) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error", error)
            runCatching {
                player.stop()
                player.clearMediaItems()
            }
            updateNowPlaying(player)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"

        private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
        val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

        @Volatile
        private var activePlayer: Player? = null

        @Volatile
        private var activeSession: MediaSession? = null

        private fun updateNowPlaying(player: Player) {
            val item = player.currentMediaItem
            _nowPlaying.value = if (item == null || player.playbackState == Player.STATE_IDLE) {
                null
            } else {
                NowPlaying(
                    uri = Uri.parse(item.mediaId),
                    title = item.mediaMetadata.title?.toString() ?: "Video",
                    isPlaying = player.isPlaying,
                )
            }
        }

        /**
         * Stops and clears the player when [uri] is the current item. Call this before
         * deleting a MediaStore file so ExoPlayer is not holding a dead Uri.
         */
        fun stopIfPlaying(uri: Uri) {
            val player = activePlayer ?: return
            val current = player.currentMediaItem?.mediaId ?: return
            if (current != uri.toString()) return
            runCatching {
                player.stop()
                player.clearMediaItems()
            }
            _nowPlaying.value = null
        }

        fun mediaItem(uri: Uri, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist("TubeOffline")
                    .build(),
            )
            .build()

        private fun maybeAutoplayNext(context: android.content.Context, player: Player, endedUri: Uri) {
            if (!AppPreferences.isAutoplayEnabled(context)) return
            val next = runCatching {
                VideoStore.nextAfter(context, endedUri) { video ->
                    PlaybackPositionStore.isCompleted(context, video.uri)
                }
            }.getOrNull() ?: return
            val title = next.name.substringBeforeLast('.')
            val item = mediaItem(next.uri, title)
            runCatching {
                player.setMediaItem(resolveForPlayer(item))
                player.prepare()
                player.playWhenReady = true
                refreshSessionActivity(context, activeSession, item)
                updateNowPlaying(player)
            }.onFailure { Log.e(TAG, "Autoplay next failed", it) }
        }

        private fun resolveForPlayer(item: MediaItem): MediaItem {
            val uri = Uri.parse(item.mediaId)
            val ext = uri.lastPathSegment?.substringAfterLast('.', "") ?: "mp4"
            return item.buildUpon()
                .setUri(uri)
                .setMimeType(VideoStore.mimeFor(ext))
                .build()
        }

        private fun refreshSessionActivity(
            context: android.content.Context,
            session: MediaSession?,
            item: MediaItem,
        ) {
            val sessionRef = session ?: activeSession ?: return
            val uri = Uri.parse(item.mediaId)
            val title = item.mediaMetadata.title?.toString() ?: "Video"
            val openPlayer = PendingIntent.getActivity(
                context,
                0,
                PlayerActivity.playIntent(context, uri, title)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            runCatching { sessionRef.setSessionActivity(openPlayer) }
        }
    }
}

data class NowPlaying(
    val uri: Uri,
    val title: String,
    val isPlaying: Boolean,
)
