package com.pisotiyempo

import android.app.Application
import com.pisotiyempo.work.Notifications
import com.pisotiyempo.work.SyncService

class PisoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        SyncService.schedule(this)
    }
}
