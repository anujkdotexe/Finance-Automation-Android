package com.financeautomation.presentation

import com.financeautomation.presentation.ui.AppRoutes
import com.financeautomation.presentation.ui.MainRouteValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** X-2: outside callers can only open dashboard, needs review and transactions; internal intents keep the full list. */
class ExternalRouteRestrictionTest {

    private val restricted = listOf(
        AppRoutes.SYNC_BACKUP, AppRoutes.CAPTURE_PERMISSIONS, AppRoutes.DIAGNOSTICS, AppRoutes.PREFERENCES,
        AppRoutes.ONBOARDING, AppRoutes.ACCOUNTS, AppRoutes.ABOUT
    )

    @Test
    fun externalExtraRouteIsLimitedToTheThreeSafeScreens() {
        assertEquals(AppRoutes.DASHBOARD, MainRouteValidator.resolveRoute(AppRoutes.DASHBOARD, null))
        assertEquals(AppRoutes.NEEDS_REVIEW, MainRouteValidator.resolveRoute(AppRoutes.NEEDS_REVIEW, null))
        assertEquals(AppRoutes.TRANSACTIONS, MainRouteValidator.resolveRoute(AppRoutes.TRANSACTIONS, null))
        for (route in restricted) {
            assertNull("external extra_route $route must be rejected", MainRouteValidator.resolveRoute(route, null))
        }
    }

    @Test
    fun externalDeepLinkIsLimitedToTheThreeSafeScreens() {
        for (scheme in listOf("financeautomation", "finance")) {
            assertEquals(AppRoutes.DASHBOARD, MainRouteValidator.resolveRoute(null, "$scheme://dashboard"))
            assertEquals(AppRoutes.NEEDS_REVIEW, MainRouteValidator.resolveRoute(null, "$scheme://route/needsReview"))
            assertEquals(AppRoutes.TRANSACTIONS, MainRouteValidator.resolveRoute(null, "$scheme://transactions"))
            for (route in restricted) {
                assertNull("deep link $route must be rejected with scheme $scheme", MainRouteValidator.resolveRoute(null, "$scheme://route/$route"))
            }
        }
    }

    @Test
    fun internalIntentsKeepTheFullRouteList() {
        for (route in MainRouteValidator.ALLOWED_ROUTES) {
            assertEquals(route, MainRouteValidator.resolveRoute(route, null, internal = true))
        }
    }
}
