package com.godbless.lockin.auth

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Fingerprint / face / screen-lock check using the system prompt. */
object Biometrics {
    private val allowed: Int
        get() = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }

    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(allowed) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(allowed)
            .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
