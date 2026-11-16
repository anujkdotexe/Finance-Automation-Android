package com.financeautomation.presentation.screens.drawer

import android.content.ActivityNotFoundException
import com.financeautomation.presentation.ui.AppRoutes
import com.financeautomation.presentation.ui.MainRouteValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutPrivacyAndLicencesRoutesTest {

    @Test
    fun testPrivacyPolicyAndLicencesRoutesAreWhitelisted() {
        assertEquals(AppRoutes.PRIVACY_POLICY, MainRouteValidator.validateRoute(AppRoutes.PRIVACY_POLICY))
        assertEquals(AppRoutes.OPEN_SOURCE_LICENCES, MainRouteValidator.validateRoute(AppRoutes.OPEN_SOURCE_LICENCES))
        assertTrue(MainRouteValidator.ALLOWED_ROUTES.contains(AppRoutes.PRIVACY_POLICY))
        assertTrue(MainRouteValidator.ALLOWED_ROUTES.contains(AppRoutes.OPEN_SOURCE_LICENCES))
    }

    @Test
    fun testExternalLinkHelperTriggersOnErrorWhenActivityNotFound() {
        var errorTriggered = false
        launchExternalLink(
            url = "https://example.com",
            startActivity = { throw ActivityNotFoundException("Activity not found") },
            onError = { errorTriggered = true }
        )
        assertTrue("onError callback must be executed when browser activity cannot be launched", errorTriggered)
    }

    @Test
    fun testExternalLinkHelperLaunchesIntentSuccessfully() {
        var launched = false
        launchExternalLink(
            url = "https://example.com",
            startActivity = { launched = true },
            onError = {}
        )
        assertTrue("startActivity must be invoked for valid URL", launched)
    }
}
