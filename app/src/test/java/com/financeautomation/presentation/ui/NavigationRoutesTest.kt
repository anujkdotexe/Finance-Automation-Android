package com.financeautomation.presentation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * navigation-testing is not available, so the NavHostController helpers are checked at source level; the route
 * builder is pure and is tested by running it.
 */
class NavigationRoutesTest {

    private val root = File("src/main/java/com/financeautomation/presentation")
    private val host = File(root, "ui/AppNavHost.kt").readText()
    private val main = File(root, "ui/MainActivity.kt").readText()

    @Test
    fun routeArgumentsFromUserTextAreEncoded() {
        assertEquals("transactions?monthOffset=0", transactionsRoute(0, null, null))
        assertEquals("transactions?monthOffset=-1&category=Food%20%26%20Drink", transactionsRoute(-1, "Food & Drink", null))
        assertEquals("transactions?monthOffset=0&category=a%2Fb%3Fc%3Dd%25&type=living_burn", transactionsRoute(0, "a/b?c=d%", "living_burn"))
        assertEquals("a blank category adds nothing", "transactions?monthOffset=2", transactionsRoute(2, "  ", ""))
    }

    @Test
    fun theDashboardBuildsItsTransactionsRouteWithTheEncoder() {
        assertTrue(host.contains("transactionsRoute(monthOffset, category, type)"))
        assertFalse(host.contains("append(\"&category=\").append(category)"))
    }

    @Test
    fun everyPlainNavigateGoesThroughTheHelperSoStacksCannotStack() {
        val body = host.substringAfter("fun AppNavHost(")
        val plain = Regex("navController\\.navigate\\(AppRoutes\\.[A-Z_]+\\)(?!\\s*\\{)").findAll(body).count()
        assertEquals("a bare navigate(route) without launchSingleTop is left", 0, plain)
        assertTrue(host.contains("internal fun NavHostController.navigateTop("))
    }

    @Test
    fun backArrowsNeverEmptyTheHost() {
        val body = host.substringAfter("fun AppNavHost(")
        assertEquals("a bare popBackStack() is left", 0, Regex("navController\\.popBackStack\\(\\)").findAll(body).count())
        val helper = host.substringAfter("internal fun NavHostController.goBackOrDashboard()").substringBefore("}\n")
        assertTrue(helper.contains("previousBackStackEntry"))
        assertTrue(helper.contains("goToDashboard()"))
    }

    @Test
    fun externalRoutesGoThroughTheHelpers() {
        assertTrue(main.contains("navController.navigateToRoute(route)"))
        assertFalse(main.contains("navController.navigate(route)"))
        val helper = host.substringAfter("internal fun NavHostController.navigateToRoute(")
        assertTrue(helper.contains("goToDashboard()"))
        assertTrue(helper.contains("goToNeedsReview()"))
    }
}
