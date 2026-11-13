package com.financeautomation.presentation.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source level checks that budget bars are shown where budgets matter and that moved texts stay in strings.xml. */
class BudgetWiringAndStringsTest {

    private val base = "src/main/java/com/financeautomation/presentation/"
    private fun read(path: String) = File(base + path).readText().replace("\r\n", "\n")
    private val strings = File("src/main/res/values/strings.xml").readText()

    @Test
    fun theDashboardHasExactlyOneBudgetBarPerCategoryRow() {
        val section = read("screens/DashboardCategorySection.kt")
        assertTrue(section.contains("progress = item.budgetProgress"))
        assertTrue(section.split("BudgetBar(").size - 1 == 1)
        assertFalse(read("screens/DashboardScreen.kt").contains("budgetProgress = state.budgetProgress"))
        assertTrue(read("ui/MainActivity.kt").contains("budgetRepository = app.appContainer.budgetRepository"))
    }

    @Test
    fun theTransactionsScreenShowsABudgetBarWhenACategoryIsFiltered() {
        assertTrue(read("screens/TransactionsScreen.kt").contains("state.categoryBudget?.let"))
        assertTrue(read("screens/TransactionsScreen.kt").contains("BudgetBar(progress = progress)"))
    }

    @Test
    fun savingsAndTransferRowsHaveNoBudgetLine() {
        val screen = read("screens/drawer/CategoriesScreen.kt")
        val afterLocked = screen.substringAfter("categories_always_excluded")
        assertTrue(afterLocked.contains("if (!isLocked) {\n                        Text(\n                            text = if (budgetAmount != null)"))
    }

    @Test
    fun movedTextsAreNoLongerLiteralsAndLiveInStringsXml() {
        val moved = mapOf(
            "screens/drawer/CategoriesScreen.kt" to listOf("\"Edit Category\"", "\"Category Name\"", "\"Hide category\""),
            "screens/drawer/AccountsScreen.kt" to listOf("\"Edit Account\"", "\"Set as default account\"", "\"Archive account\""),
            "screens/drawer/SyncBackupScreen.kt" to listOf("\"Disconnect Google account?\"", "\"Regenerate dashboard formulas\""),
            "screens/drawer/PeopleTripsScreen.kt" to listOf("\"Friend details\"", "\"Save name\""),
            "screens/DashboardCategorySection.kt" to listOf("\"This month\"", "\"Previous year\"")
        )
        moved.forEach { (file, literals) ->
            val source = read(file)
            literals.forEach { assertFalse("$file still has $it", source.contains(it)) }
        }
        listOf("categories_edit_title", "accounts_edit_title", "sync_disconnect_title", "friends_details_title", "dashboard_picker_this_month")
            .forEach { assertTrue(strings.contains("name=\"$it\"")) }
    }

    @Test
    fun accountFilterValuesStayPlainTextBecauseTheyAreMatchedAgainstData() {
        val screen = read("screens/TransactionsScreen.kt")
        assertTrue(screen.contains("currentAccount.equals(\"UPI\", ignoreCase = true)"))
        assertTrue(screen.contains("else \"Card\""))
    }

    @Test
    fun job4MovedTextsAreNoLongerLiteralsAndLiveInStringsXml() {
        val moved = mapOf(
            "screens/drawer/DiagnosticsScreen.kt" to listOf("\"Share Diagnostics\"", "\"Simulation Mode\"", "\"SMS simulation completed\""),
            "components/QuickLogSheet.kt" to listOf("\"DATE\"", "\"Today\"", "\"Yesterday\"", "\"Pick date\""),
            "screens/DashboardBurnCards.kt" to listOf("\"to review\""),
            "screens/ReviewInboxScreen.kt" to listOf("\"today\"", "\"Synced\""),
            "screens/OnboardingScreen.kt" to listOf("\"Setup\""),
            "screens/drawer/SubscriptionsScreen.kt" to listOf("\"per month\""),
            "screens/drawer/AddSubscriptionSheet.kt" to listOf("\"INR (₹)\"", "\"In 4 days\""),
            "screens/TransactionsScreen.kt" to listOf("\"Living burn ✕\"", "\"Transaction voided\"", "\"Entry voided\""),
            "components/SyncStatusLine.kt" to listOf("\"failed, tap to fix\""),
            "ui/AppNavHost.kt" to listOf("\"Open navigation menu\"", "\"Navigate back\"")
        )
        moved.forEach { (file, literals) ->
            val source = read(file)
            literals.forEach { assertFalse("$file still has $it", source.contains(it)) }
        }
        val expectedResourceKeys = listOf(
            "diag_share_chooser_title",
            "diag_sim_card_title",
            "quick_log_date_label",
            "dashboard_key_figures_cd",
            "onboarding_setup_title",
            "subscriptions_per_month",
            "currency_inr",
            "subscription_remind_in_4_days",
            "txns_filter_living_burn_dismiss",
            "companion_split_people_count",
            "sync_status_failed_tap_to_fix"
        )
        expectedResourceKeys.forEach {
            assertTrue("strings.xml missing $it", strings.contains("name=\"$it\""))
        }
    }
}
