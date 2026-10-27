package com.pisotiyempo

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pisotiyempo.ui.nav.AppNav
import com.pisotiyempo.ui.onboarding.DisclaimerScreen
import com.pisotiyempo.ui.theme.PisoTheme
import com.pisotiyempo.work.Notifications
import com.pisotiyempo.work.SyncService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingSymbol by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingSymbol = intent?.getStringExtra(Notifications.EXTRA_SYMBOL)
        val container = (application as PisoApp).container

        setContent {
            PisoTheme {
                val settings by container.settings.settings.collectAsStateWithLifecycle(null)
                val scope = rememberCoroutineScope()
                val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val s = settings
                    when {
                        s == null -> Unit // DataStore is loading; this takes a few milliseconds.
                        !s.disclaimerAccepted -> DisclaimerScreen(onAccept = {
                            scope.launch {
                                container.settings.acceptDisclaimer()
                                SyncService.syncNow(this@MainActivity)
                            }
                        })
                        else -> {
                            LaunchedEffect(Unit) {
                                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            AppNav(openSymbol = pendingSymbol, onSymbolOpened = { pendingSymbol = null })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_SYMBOL)?.let { pendingSymbol = it }
    }
}
