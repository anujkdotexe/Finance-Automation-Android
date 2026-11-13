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

    private val _appLockOn = MutableStateFlow(false)
    private val _secure = MutableStateFlow(initial)

    /**
     * True when windows must be marked secure (no screenshots, hidden in recents): the "hide preview" setting is on
     * or the app lock is on. With the app lock on the preview is always hidden, because the recents picture is taken
     * the moment the app leaves the screen, before the lock timeout.
     */
    val secure: StateFlow<Boolean> = _secure.asStateFlow()

    fun set(hide: Boolean) {
        _hideRecentsPreview.value = hide
        _secure.value = hide || _appLockOn.value
    }

    fun setAppLockOn(on: Boolean) {
        _appLockOn.value = on
        _secure.value = _hideRecentsPreview.value || on
    }
}
