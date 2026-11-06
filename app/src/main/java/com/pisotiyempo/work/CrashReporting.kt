package com.pisotiyempo.work

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Crashlytics, when the build includes a Firebase config; a no-op otherwise. */
object CrashReporting {
    fun setEnabled(context: Context, enabled: Boolean) {
        // Without google-services.json there is no FirebaseApp, and getInstance() would throw.
        if (FirebaseApp.getApps(context).isEmpty()) return
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)
    }
}
