package com.tubeoffline.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TubeOfflineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                "default_channel_id",
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }
}
