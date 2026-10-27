package com.tubeoffline.app

import android.app.Application

class TubeOfflineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
