package com.financeautomation.domain.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pure logic of the optional app lock. The app never stores a PIN: the phone's own screen lock or biometrics do
 * the authentication, this class only decides when the app counts as locked.
 *
 * - Off by default; while off the app never locks.
 * - The first [onColdStart] of a process locks the app when the lock is on.
 * - After the app has been in the background for [timeoutMillis] or longer (60 seconds by default) the next return
 *   to the app is locked.
 * - Turning the lock on needs a successful authentication first ([enable]).
 * Pure Kotlin with an injectable clock, no Android framework dependencies.
 */
class AppLockManager(
    private val clock: () -> Long = System::currentTimeMillis,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
) {
    private val lock = Any()
    private val _locked = MutableStateFlow(false)

    /** True while the lock screen must be shown instead of the app. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var enabled = false
    private var started = false
    private var backgroundedAt: Long? = null

    val isEnabled: Boolean get() = synchronized(lock) { enabled }
    val isStarted: Boolean get() = synchronized(lock) { started }

    /**
     * Called once per process with the saved setting. Later calls are ignored, so recreating an activity (for example
     * a rotation) never locks the app again.
     */
    fun onColdStart(lockEnabled: Boolean) = synchronized(lock) {
        if (started) return@synchronized
        started = true
        enabled = lockEnabled
        _locked.value = lockEnabled
    }

    /** The app moved to the background. */
    fun onBackgrounded() = synchronized(lock) {
        if (backgroundedAt == null) backgroundedAt = clock()
    }

    /** The app came back to the foreground; locks when it was away for the timeout or longer. */
    fun onForegrounded() = synchronized(lock) {
        val since = backgroundedAt
        backgroundedAt = null
        if (enabled && since != null && clock() - since >= timeoutMillis) {
            _locked.value = true
        }
    }

    /**
     * True when data must be hidden right now. Also covers the case where the app has been in the background past the
     * timeout but has not been brought back yet, so quick add screens opened from a notification stay safe.
     */
    fun isLockedNow(): Boolean = synchronized(lock) {
        if (!enabled) return@synchronized false
        if (_locked.value) return@synchronized true
        val since = backgroundedAt ?: return@synchronized false
        clock() - since >= timeoutMillis
    }

    /** Records the result of an unlock attempt. Returns true when the app is now unlocked. */
    fun onUnlockResult(authenticated: Boolean): Boolean = synchronized(lock) {
        if (authenticated) {
            _locked.value = false
            backgroundedAt = null
        }
        authenticated
    }

    /**
     * Turns the lock on. Needs [authenticated] to be true (a successful authentication just happened); otherwise the
     * lock stays off and false is returned. The app stays unlocked for the current session.
     */
    fun enable(authenticated: Boolean): Boolean = synchronized(lock) {
        if (!authenticated) return@synchronized false
        enabled = true
        started = true
        _locked.value = false
        backgroundedAt = null
        true
    }

    /** Turns the lock off; the app can never be locked while it is off. */
    fun disable() = synchronized(lock) {
        enabled = false
        _locked.value = false
        backgroundedAt = null
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS: Long = 60_000L
    }
}

/** Which kinds of authentication the phone has to offer, as reported by the platform. */
enum class AuthAvailability { AVAILABLE, NONE_ENROLLED, NO_HARDWARE, UNAVAILABLE }

/** Chooses the allowed authenticator types for the platform version. */
object AuthenticatorPolicy {
    const val BIOMETRIC_STRONG = 0x000F
    const val BIOMETRIC_WEAK = 0x00FF
    const val DEVICE_CREDENTIAL = 0x8000

    /**
     * Android 11 and newer accept strong biometrics or the device credential together. On Android 9 and 10 that
     * combination is not supported by the platform, so weak biometrics or the device credential is used instead.
     */
    fun allowedAuthenticators(sdkInt: Int): Int =
        if (sdkInt >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
}
