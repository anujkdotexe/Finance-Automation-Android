package com.financeautomation.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockManagerTest {

    private var now = 1_000_000L
    private val manager = AppLockManager(clock = { now })

    private fun backgroundFor(millis: Long) {
        manager.onBackgrounded()
        now += millis
        manager.onForegrounded()
    }

    @Test
    fun coldStartIsLockedWhenTheLockIsOn() {
        manager.onColdStart(lockEnabled = true)
        assertTrue(manager.locked.value)
        assertTrue(manager.isLockedNow())
    }

    @Test
    fun coldStartIsOpenWhenTheLockIsOff() {
        manager.onColdStart(lockEnabled = false)
        assertFalse(manager.locked.value)
        assertFalse(manager.isLockedNow())
    }

    @Test
    fun recreatingTheActivityDoesNotLockAgain() {
        manager.onColdStart(lockEnabled = true)
        manager.onUnlockResult(true)
        manager.onColdStart(lockEnabled = true)
        assertFalse(manager.locked.value)
    }

    @Test
    fun timeoutBoundariesAt59_60_61Seconds() {
        manager.onColdStart(true)
        manager.onUnlockResult(true)

        backgroundFor(59_000L)
        assertFalse("59 s stays open", manager.locked.value)

        backgroundFor(60_000L)
        assertTrue("60 s locks", manager.locked.value)

        manager.onUnlockResult(true)
        backgroundFor(61_000L)
        assertTrue("61 s locks", manager.locked.value)
    }

    @Test
    fun aShortTripToTheBackgroundDoesNotLock() {
        manager.onColdStart(true)
        manager.onUnlockResult(true)
        backgroundFor(1_000L)
        assertFalse(manager.locked.value)
    }

    @Test
    fun switchOffNeverLocks() {
        manager.onColdStart(false)
        backgroundFor(10 * 60_000L)
        assertFalse(manager.locked.value)
        assertFalse(manager.isLockedNow())
    }

    @Test
    fun enableRequiresAuthenticationSuccess() {
        manager.onColdStart(false)

        assertFalse(manager.enable(authenticated = false))
        assertFalse(manager.isEnabled)
        backgroundFor(5 * 60_000L)
        assertFalse(manager.locked.value)

        assertTrue(manager.enable(authenticated = true))
        assertTrue(manager.isEnabled)
        assertFalse("stays open for the current session", manager.locked.value)
        backgroundFor(60_000L)
        assertTrue(manager.locked.value)
    }

    @Test
    fun disablingUnlocksAndStopsLocking() {
        manager.onColdStart(true)
        manager.disable()
        assertFalse(manager.locked.value)
        backgroundFor(10 * 60_000L)
        assertFalse(manager.locked.value)
        assertFalse(manager.isEnabled)
    }

    @Test
    fun aFailedUnlockKeepsTheAppLocked() {
        manager.onColdStart(true)
        assertFalse(manager.onUnlockResult(false))
        assertTrue(manager.locked.value)
        assertTrue(manager.onUnlockResult(true))
        assertFalse(manager.locked.value)
    }

    @Test
    fun isLockedNowCoversTheBackgroundGapBeforeTheAppReturns() {
        manager.onColdStart(true)
        manager.onUnlockResult(true)
        manager.onBackgrounded()

        now += 59_000L
        assertFalse(manager.isLockedNow())
        now += 1_000L
        assertTrue("a quick add opened after the timeout must see the app as locked", manager.isLockedNow())
    }

    @Test
    fun theFirstBackgroundMomentCountsWhenBackgroundedTwice() {
        manager.onColdStart(true)
        manager.onUnlockResult(true)
        manager.onBackgrounded()
        now += 40_000L
        manager.onBackgrounded()
        now += 20_000L
        manager.onForegrounded()
        assertTrue(manager.locked.value)
    }

    @Test
    fun authenticatorPolicyAvoidsTheUnsupportedComboBeforeAndroid11() {
        val modern = AuthenticatorPolicy.allowedAuthenticators(30)
        assertEquals(AuthenticatorPolicy.BIOMETRIC_STRONG or AuthenticatorPolicy.DEVICE_CREDENTIAL, modern)
        assertEquals(modern, AuthenticatorPolicy.allowedAuthenticators(35))
        val legacy = AuthenticatorPolicy.allowedAuthenticators(28)
        assertEquals(AuthenticatorPolicy.BIOMETRIC_WEAK or AuthenticatorPolicy.DEVICE_CREDENTIAL, legacy)
        assertEquals(legacy, AuthenticatorPolicy.allowedAuthenticators(29))
    }
}
