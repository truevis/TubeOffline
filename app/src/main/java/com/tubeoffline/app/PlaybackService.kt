package com.tubeoffline.app

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

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
            .build()
        player.addListener(PlaybackProgressListener(this, player))
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
    ) : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val uri = player.currentMediaItem?.mediaId?.let(Uri::parse) ?: return
            if (player.playbackState != Player.STATE_READY && player.playbackState != Player.STATE_BUFFERING) return
            PlaybackPositionStore.save(context, uri, player.currentPosition, paused = !isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val item = player.currentMediaItem ?: return
            val uri = Uri.parse(item.mediaId)
            when (playbackState) {
                Player.STATE_ENDED -> PlaybackPositionStore.clear(context, uri)
                Player.STATE_READY -> {
                    PlaybackPositionStore.clearIfNearEnd(
                        context,
                        uri,
                        player.currentPosition,
                        player.duration,
                    )
                }
            }
        }
    }

    companion object {
        fun mediaItem(uri: Uri, title: String): MediaItem = MediaItem.Builder()
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist("TubeOffline")
                    .build(),
            )
            .build()
    }
}
