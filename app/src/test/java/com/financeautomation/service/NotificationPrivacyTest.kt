package com.financeautomation.service

import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R2-5: with app lock on, notifications that carry a payee or an amount are private on the lock screen. */
class NotificationPrivacyTest {

    @Test
    fun appLockOnMakesTheNotificationPrivateWithAPublicVersion() {
        val p = NotificationPrivacy.policy(appLockOn = true)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, p.visibility)
        assertTrue(p.usePublicVersion)
    }

    @Test
    fun policyWithLockOffIsPrivateWithPublicVersion() {
        val p = NotificationPrivacy.policy(appLockOn = false)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, p.visibility)
        assertTrue(p.usePublicVersion)
    }

    @Test
    fun ifTheLockSettingCannotBeReadTheNotificationStaysPrivate() = runBlocking {
        assertTrue(NotificationPrivacy.resolveAppLockOn { throw IllegalStateException("storage") })
        assertTrue(NotificationPrivacy.resolveAppLockOn { true })
        assertFalse(NotificationPrivacy.resolveAppLockOn { false })
    }
}
