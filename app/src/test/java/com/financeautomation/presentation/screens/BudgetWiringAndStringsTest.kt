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
}
