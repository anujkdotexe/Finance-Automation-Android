package com.financeautomation.presentation.security

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.financeautomation.domain.security.AuthAvailability
import com.financeautomation.domain.security.AuthenticatorPolicy

/**
 * Thin wrapper over the platform BiometricPrompt: strong biometrics or the phone's screen lock. The app never sees
 * or stores any PIN, pattern or fingerprint data; it only learns whether the owner passed.
 */
class AppAuthenticator(private val activity: FragmentActivity) {

    private val authenticators: Int get() = AuthenticatorPolicy.allowedAuthenticators(Build.VERSION.SDK_INT)

    /** Whether the phone has a screen lock or biometrics set up that can be used. */
    fun availability(): AuthAvailability =
        when (BiometricManager.from(activity).canAuthenticate(authenticators)) {
            BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> AuthAvailability.NONE_ENROLLED
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> AuthAvailability.NO_HARDWARE
            else -> AuthAvailability.UNAVAILABLE
        }

    /** Shows the system prompt. [onResult] is called once with true on success and false on failure or cancel. */
    fun authenticate(title: String, subtitle: String?, onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onResult(false)
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}
