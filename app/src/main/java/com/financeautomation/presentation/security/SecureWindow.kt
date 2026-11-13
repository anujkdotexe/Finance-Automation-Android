package com.financeautomation.presentation.security

import android.view.Window
import android.view.WindowManager
import com.financeautomation.domain.security.PrivacySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Applies or clears FLAG_SECURE, which blocks screenshots and hides the window in the recent apps list.
 */
object SecureWindow {

    fun apply(window: Window, secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Adds or clears FLAG_SECURE on the layout parameters of an overlay window before it is attached. */
    fun apply(params: WindowManager.LayoutParams, secure: Boolean) {
        params.flags = if (secure) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
    }

    /** Keeps [window] in step with the setting for as long as [scope] lives. */
    fun follow(window: Window, settings: PrivacySettings, scope: CoroutineScope) {
        apply(window, settings.secure.value)
        scope.launch(Dispatchers.Main.immediate) {
            settings.secure.collect { apply(window, it) }
        }
    }
}
