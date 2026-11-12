package com.financeautomation.data.repository

import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionReminderPreferenceTest {

    @Test
    fun defaultIsTwentyOneHundred() = runBlocking {
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        assertEquals("21:00", prefs.getSubscriptionReminderTimeSync())
        assertEquals("21:00", prefs.getSubscriptionReminderTime().first())
    }

    @Test
    fun setTimeIsStoredZeroPaddedAndRead() = runBlocking {
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        prefs.setSubscriptionReminderTime("7:05")
        assertEquals("07:05", prefs.getSubscriptionReminderTimeSync())
        assertEquals("07:05", prefs.getPreference(PreferencesRepository.KEY_SUBSCRIPTION_REMINDER_TIME))
    }

    @Test
    fun invalidTimeIsIgnored() = runBlocking {
        val prefs = PreferencesRepositoryImpl(isTestHeadless = true)
        prefs.setSubscriptionReminderTime("25:00")
        prefs.setSubscriptionReminderTime("abc")
        assertNull(prefs.getPreference(PreferencesRepository.KEY_SUBSCRIPTION_REMINDER_TIME))
        assertEquals("21:00", prefs.getSubscriptionReminderTimeSync())
    }

    @Test
    fun parseHelpers() {
        assertEquals("09:30", PreferencesRepository.parseReminderTime(" 9:30 "))
        assertNull(PreferencesRepository.parseReminderTime("24:00"))
        assertNull(PreferencesRepository.parseReminderTime("12:60"))
        assertEquals("21:00", PreferencesRepository.normalizeReminderTime(null))
    }
}
