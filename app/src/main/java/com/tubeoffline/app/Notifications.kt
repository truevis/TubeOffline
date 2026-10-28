package com.tubeoffline.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_RESULT = "result"
    const val ID_PROGRESS = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Download finished / failed", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun progress(
        context: Context,
        title: String,
        text: String,
        percent: Int?,
    ) = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
        .setSmallIcon(R.drawable.ic_stat_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setSilent(true)
        .setContentIntent(openAppIntent(context))
        .setProgress(100, percent ?: 0, percent == null)
        .addAction(
            0, "Cancel",
            PendingIntent.getService(
                context, 1,
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    fun success(context: Context, name: String, uri: Uri, sizeBytes: Long) {
        val view = PendingIntent.getActivity(
            context, uri.hashCode(),
            VideoStore.viewIntent(context, uri, "video/mp4"),
            PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context, uri.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Saved for offline")
                .setContentText("$name (${formatSize(sizeBytes)})")
                .setStyle(NotificationCompat.BigTextStyle().bigText("$name\n${formatSize(sizeBytes)} · Movies/TubeOffline"))
                .setContentIntent(view)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun failure(context: Context, url: String, message: String) {
        post(
            context, url.hashCode(),
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Download failed")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$message\n\n$url"))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
