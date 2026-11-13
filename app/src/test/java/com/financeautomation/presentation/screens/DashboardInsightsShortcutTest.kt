package com.financeautomation.presentation.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Verifies that the Insights shortcut on the Dashboard is properly configured and wired to navigate. */
class DashboardInsightsShortcutTest {

    private val base = "src/main/java/com/financeautomation/presentation/"
    private fun read(path: String) = File(base + path).readText().replace("\r\n", "\n")
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun dashboardCategorySectionWiresInsightsShortcut() {
        val section = read("screens/DashboardCategorySection.kt")
        assertTrue(section.contains("onInsightsClick: (() -> Unit)? = null"))
        assertTrue(section.contains("secondaryActionText = if (onInsightsClick != null) stringResource(R.string.insights_title) else null"))
        assertTrue(section.contains("secondaryActionContentDescription = stringResource(R.string.dashboard_insights_link_cd)"))
    }

    @Test
    fun dashboardScreenWiresInsightsCallback() {
        val screen = read("screens/DashboardScreen.kt")
        assertTrue(screen.contains("onNavigateToInsights: () -> Unit = {}"))
        assertTrue(screen.contains("onInsightsClick = onNavigateToInsights"))
    }

    @Test
    fun appNavHostNavigatesToInsightsRoute() {
        val nav = read("ui/AppNavHost.kt")
        assertTrue(nav.contains("onNavigateToInsights = {"))
        assertTrue(nav.contains("navController.navigate(AppRoutes.INSIGHTS)"))
    }

    @Test
    fun sectionTitleEnforces48dpTouchTargetForActions() {
        val title = read("components/SectionTitle.kt")
        assertTrue(title.contains("secondaryActionContentDescription: String? = null"))
        assertTrue(title.contains("defaultMinSize(minWidth = FinanceTheme.sizes.minTouchTarget, minHeight = FinanceTheme.sizes.minTouchTarget)"))
    }

    @Test
    fun stringResourcesExistForInsightsShortcut() {
        assertTrue(strings.contains("name=\"insights_title\""))
        assertTrue(strings.contains("name=\"dashboard_insights_link_cd\""))
    }
}
