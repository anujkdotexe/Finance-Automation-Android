package com.financeautomation.domain.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live value of the "Hide app preview in recent apps" setting, read by every window the app opens.
 * Until the saved setting has been loaded the value is true, so no window ever shows content in the recents list by
 * mistake; loading usually takes a few milliseconds.
 */
class PrivacySettings(initial: Boolean = true) {
    private val _hideRecentsPreview = MutableStateFlow(initial)
    val hideRecentsPreview: StateFlow<Boolean> = _hideRecentsPreview.asStateFlow()

    fun set(hide: Boolean) {
        _hideRecentsPreview.value = hide
    }
}
