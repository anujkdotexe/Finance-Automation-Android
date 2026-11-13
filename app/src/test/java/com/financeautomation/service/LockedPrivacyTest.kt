package com.financeautomation.service

import com.financeautomation.domain.security.PrivacySettings
import com.financeautomation.domain.usecase.MonthBurnSummary
import com.financeautomation.domain.usecase.WidgetSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** R2-6 and R2-7: with app lock on the widget hides the amount, and every window is marked secure. */
class LockedPrivacyTest {

    private val labels = MonthBurnWidgetLabels(
        title = "Living burn",
        perDayTemplate = "%1\$s a day",
        upToDate = "Up to date",
        waitingTemplate = "%1\$d waiting",
        descriptionTemplate = "%1\$s living burn %2\$s, %3\$s, %4\$s",
        unlockToView = "Unlock to view"
    )

    private val summary = MonthBurnSummary(
        year = 2026, month = 11, livingBurn = 12345.0, perDayAverage = 950.0, daysElapsed = 13,
        syncState = WidgetSyncState.Waiting(2)
    )

    @Test
    fun lockedWidgetShowsTheMonthAndUnlockToViewOnly() {
        val c = MonthBurnWidgetFormatter.format(summary, labels, Locale.US, appLockOn = true)
        assertEquals("November", c.monthName)
        assertEquals("Unlock to view", c.amount)
        assertEquals("", c.perDay)
        assertEquals("", c.syncState)
        assertFalse(c.description.contains("12"))
        assertTrue(c.description.contains("Unlock to view"))
    }

    @Test
    fun unlockedWidgetStillShowsTheFigures() {
        val c = MonthBurnWidgetFormatter.format(summary, labels, Locale.US, appLockOn = false)
        assertTrue(c.amount.contains("12,345"))
        assertTrue(c.syncState.contains("2"))
    }

    @Test
    fun windowsAreSecureWhenTheAppLockIsOnEvenIfHidePreviewIsOff() {
        val s = PrivacySettings()
        s.set(false)
        s.setAppLockOn(false)
        assertFalse(s.secure.value)
        s.setAppLockOn(true)
        assertTrue(s.secure.value)
        s.set(true)
        s.setAppLockOn(false)
        assertTrue(s.secure.value)
    }
}
