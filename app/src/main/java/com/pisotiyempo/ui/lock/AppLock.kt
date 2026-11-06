package com.pisotiyempo.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.pisotiyempo.R
import com.pisotiyempo.ui.common.MutedText

/**
 * Fingerprint, face or screen-lock PIN before the app opens. BIOMETRIC_WEAK | DEVICE_CREDENTIAL is the one
 * combination Android supports on every version this app runs on (API 26+), and the PIN fallback means a
 * user whose fingerprint stops working isn't locked out.
 */
object AppLock {
    private const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Null when the device can lock the app; otherwise why not, for the Settings screen. */
    fun unavailableReason(context: Context): String? = when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> context.getString(R.string.lock_none_enrolled)
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE, BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> context.getString(R.string.lock_no_hardware)
        else -> context.getString(R.string.lock_unavailable)
    }

    fun prompt(activity: FragmentActivity, onUnlocked: () -> Unit, onError: (String) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errString.toString())
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.lock_prompt_title))
                .setSubtitle(activity.getString(R.string.lock_prompt_subtitle))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build(),
        )
    }
}

@Composable
fun LockScreen(onUnlocked: () -> Unit) {
    val activity = LocalContext.current as? FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }
    fun ask() {
        if (activity == null) onUnlocked() else AppLock.prompt(activity, onUnlocked) { error = it }
    }
    LaunchedEffect(Unit) { ask() }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.lock_app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        MutedText(stringResource(R.string.lock_locked_message))
        error?.let { MutedText(it) }
        Button(onClick = { ask() }) { Text(stringResource(R.string.lock_unlock)) }
    }
}
