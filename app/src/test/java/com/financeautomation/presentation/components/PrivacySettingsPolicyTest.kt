package com.financeautomation.presentation.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySettingsPolicyTest {

    @Test
    fun testShouldShowTestReport_onlyWhenDeveloperModeAndDebugOrBeta() {
        // Without developer mode, test report is strictly hidden
        assertFalse(PrivacySettingsPolicy.shouldShowTestReport(developerMode = false, isDebugOrBeta = true))
        assertFalse(PrivacySettingsPolicy.shouldShowTestReport(developerMode = false, isDebugOrBeta = false))

        // In release without debug/beta, test report is strictly hidden even with developer mode
        assertFalse(PrivacySettingsPolicy.shouldShowTestReport(developerMode = true, isDebugOrBeta = false))

        // Only visible when developer mode is active on debug or beta build
        assertTrue(PrivacySettingsPolicy.shouldShowTestReport(developerMode = true, isDebugOrBeta = true))
    }
}
