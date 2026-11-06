package com.pisotiyempo

import android.app.Application
import com.pisotiyempo.work.CrashReporting
import com.pisotiyempo.work.Notifications
import com.pisotiyempo.work.SyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PisoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        SyncService.schedule(this)
        // Crash collection starts disabled (manifest) and follows the user's setting from here on.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            CrashReporting.setEnabled(this@PisoApp, container.settings.current().crashReports)
        }
    }
}
