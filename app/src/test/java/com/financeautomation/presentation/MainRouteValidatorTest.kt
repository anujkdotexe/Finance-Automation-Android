package com.financeautomation.presentation

import com.financeautomation.presentation.ui.AppRoutes
import com.financeautomation.presentation.ui.MainRouteValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainRouteValidatorTest {

    @Test
    fun testAllAllowedRoutes_areAccepted() {
        for (route in MainRouteValidator.ALLOWED_ROUTES) {
            val resolved = MainRouteValidator.validateRoute(route)
            assertEquals("Route $route must be accepted", route, resolved)
        }
    }

    @Test
    fun testUnknownRoutes_areRejected() {
        assertNull("Unknown route must be rejected", MainRouteValidator.validateRoute("unknownScreen"))
        assertNull("Admin route must be rejected", MainRouteValidator.validateRoute("admin"))
        assertNull("Blank route must be rejected", MainRouteValidator.validateRoute("  "))
        assertNull("Null route must be rejected", MainRouteValidator.validateRoute(null))
        assertNull("Arbitrary path must be rejected", MainRouteValidator.validateRoute("malicious/path"))
    }

    @Test
    fun testResolveRoute_fromExtraRoute() {
        val dashboard = MainRouteValidator.resolveRoute(AppRoutes.DASHBOARD, null)
        assertEquals(AppRoutes.DASHBOARD, dashboard)

        val syncBackup = MainRouteValidator.resolveRoute(AppRoutes.SYNC_BACKUP, null)
        assertEquals(AppRoutes.SYNC_BACKUP, syncBackup)

        val invalid = MainRouteValidator.resolveRoute("not_a_real_route", null)
        assertNull(invalid)
    }

    @Test
    fun testResolveRoute_fromDeepLinkUri() {
        // Form 1: financeautomation://route/capturePermissions
        val perm = MainRouteValidator.resolveRoute(null, "financeautomation://route/capturePermissions")
        assertEquals(AppRoutes.CAPTURE_PERMISSIONS, perm)

        // Form 2: financeautomation://route/syncBackup
        val sync = MainRouteValidator.resolveRoute(null, "financeautomation://route/syncBackup")
        assertEquals(AppRoutes.SYNC_BACKUP, sync)

        // Form 3: financeautomation://dashboard
        val dash = MainRouteValidator.resolveRoute(null, "financeautomation://dashboard")
        assertEquals(AppRoutes.DASHBOARD, dash)

        // Form 4: financeautomation://transactions
        val txns = MainRouteValidator.resolveRoute(null, "financeautomation://transactions")
        assertEquals(AppRoutes.TRANSACTIONS, txns)
    }

    @Test
    fun testDeepLink_withInjectedAmountsOrIds_isRejected() {
        // Deep link attempting to trigger a save or carry financial amounts
        val saveAttempt = MainRouteValidator.resolveRoute(null, "financeautomation://save?amount=5000&payee=Eve")
        assertNull("Injected save action must be rejected", saveAttempt)

        val routeWithQuery = MainRouteValidator.resolveRoute(null, "financeautomation://route/saveTransaction?amount=100")
        assertNull("Unwhitelisted route with parameters must be rejected", routeWithQuery)

        val deleteAttempt = MainRouteValidator.resolveRoute(null, "financeautomation://delete?id=all")
        assertNull("Arbitrary delete command must be rejected", deleteAttempt)
    }

    @Test
    fun testDeepLink_withInvalidScheme_isRejected() {
        val httpLink = MainRouteValidator.resolveRoute(null, "https://financeautomation/route/syncBackup")
        assertNull("Non-financeautomation scheme must be rejected", httpLink)

        val contentLink = MainRouteValidator.resolveRoute(null, "content://com.financeautomation/transactions")
        assertNull("Content URI must be rejected", contentLink)
    }
}

