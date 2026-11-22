package com.financeautomation.presentation.screens.drawer

import android.content.ActivityNotFoundException
import com.financeautomation.presentation.components.launchEmailIntent
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

    @Test
    fun testEmailIntentHelperTriggersOnErrorWhenNoEmailActivity() {
        var errorTriggered = false
        launchEmailIntent(
            email = "dev@example.com",
            startActivity = { throw ActivityNotFoundException("No mail app found") },
            onError = { errorTriggered = true }
        )
        assertTrue("onError callback must be executed when email app cannot be launched", errorTriggered)
    }

    @Test
    fun testEmailIntentHelperLaunchesIntentSuccessfully() {
        var launched = false
        launchEmailIntent(
            email = "dev@example.com",
            startActivity = { launched = true },
            onError = {}
        )
        assertTrue("startActivity must be invoked for valid email intent", launched)
    }

    @Test
    fun testReportProblemIsExposedInAboutWithoutDeveloperMode() {
        val items = getAboutNavigationItems()
        val reportItem = items.firstOrNull { it.id == "report_problem" }
        org.junit.Assert.assertNotNull("Report a problem action must be present in About navigation", reportItem)
        org.junit.Assert.assertFalse(
            "Report a problem action in About must be visible without developer mode",
            reportItem!!.requiresDeveloperMode
        )
        org.junit.Assert.assertEquals(com.financeautomation.R.string.diag_report_problem, reportItem.titleRes)

        // Verify visible rows when developer mode is false include report_problem
        val visibleItems = items.filter { !it.requiresDeveloperMode }
        assertTrue(visibleItems.any { it.id == "report_problem" })
    }

    @Test
    fun testResolveAboutItemAction_dispatchesCorrectCallbacks() {
        var privacyClicked = false
        var licencesClicked = false
        var reportClicked = false

        resolveAboutItemAction(
            itemId = "privacy",
            onNavigateToPrivacy = { privacyClicked = true },
            onNavigateToLicences = { licencesClicked = true },
            onReportProblem = { reportClicked = true }
        ).invoke()
        assertTrue("privacy action must be triggered", privacyClicked)

        resolveAboutItemAction(
            itemId = "licences",
            onNavigateToPrivacy = { privacyClicked = true },
            onNavigateToLicences = { licencesClicked = true },
            onReportProblem = { reportClicked = true }
        ).invoke()
        assertTrue("licences action must be triggered", licencesClicked)

        resolveAboutItemAction(
            itemId = "report_problem",
            onNavigateToPrivacy = { privacyClicked = true },
            onNavigateToLicences = { licencesClicked = true },
            onReportProblem = { reportClicked = true }
        ).invoke()
        assertTrue("report problem action must be triggered", reportClicked)
    }
}
