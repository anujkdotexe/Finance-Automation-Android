package com.financeautomation.data.remote

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Inflow
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.LivingBurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app figure and the figure the sheet Dashboard formula produces must be the same for the same ledger. The formula
 * is evaluated by a small reader that understands exactly the SUMIFS shapes the builder writes.
 */
class BurnFormulaParityTest {

    private val window = LivingBurn.monthWindow(2026, 11)
    private val ts = window.first + 86_400_000L * 3

    private fun txn(
        id: String, share: Double, category: String = "Food & Drink", total: Double = share, owed: Double = 0.0,
        status: TransactionStatus = TransactionStatus.PERSONAL, review: ReviewState = ReviewState.CONFIRMED
    ) = Transaction(
        id = id, timestamp = ts, dateSerial = 46000, timeSerial = 0.5, account = "SBI", merchant = id, tripTag = "",
        category = category, totalAmount = total, companions = if (owed > 0) "Asha" else "Personal", myShare = share,
        owedToMe = owed, status = status, sourcePackage = "manual", reviewState = review, syncState = SyncState.SYNCED,
        sheetRowIndex = null, isPossibleDuplicate = false
    )

    private fun inflow(id: String, type: String, amount: Double, status: String = "CONFIRMED") =
        Inflow(id = id, timestamp = ts, amount = amount, account = "SBI", inflowType = type, source = "", note = "", status = status)

    // The app ledger: a plain row, a split, a void row, Savings and Transfer (excluded), a row waiting in review
    private val appRows = listOf(
        txn("plain", 100.0),
        txn("split", share = 100.0, total = 300.0, owed = 200.0, status = TransactionStatus.PENDING),
        txn("void", share = 50.0, status = TransactionStatus.VOID),
        txn("sip", 200.0, category = "Savings"),
        txn("move", 30.0, category = "Transfer"),
        txn("review", 40.0, review = ReviewState.NEEDS_REVIEW)
    )
    private val appInflows = listOf(
        inflow("refund", "Refund / Return", 60.0),
        inflow("voidRefund", "Refund / Return", 25.0, status = "VOID"),
        inflow("pay", "Income / Salary", 5000.0)
    )

    // The same month on the sheet: voided rows are zeroed in place, the row in review was never written
    private val sheetDebits = listOf(
        mapOf("F" to "Food & Drink", "I" to 100.0),
        mapOf("F" to "Food & Drink", "I" to 100.0),
        mapOf("F" to "Food & Drink", "I" to 0.0),
        mapOf("F" to "Savings", "I" to 200.0),
        mapOf("F" to "Transfer", "I" to 30.0)
    )
    private val sheetInflows = listOf(
        mapOf("Q" to "Refund / Return", "T" to 60.0),
        mapOf("Q" to "Refund / Return", "T" to 0.0),
        mapOf("Q" to "Income / Salary", "T" to 5000.0)
    )

    /** Evaluates =IFERROR(MAX(0, SUMIFS(...) - SUMIFS(...) ...), 0) against the fixture rows. */
    private fun evaluate(formula: String): Double {
        val calls = formula.split("SUMIFS(").drop(1)
        val sums = calls.map { call ->
            val columns = Regex("""'!([A-Z]+)4:[A-Z]+"\)""").findAll(call).map { it.groupValues[1] }.toList()
            val criteria = Regex("""'![A-Z]+4:[A-Z]+"\),\s*"([^"]*)"""").findAll(call).map { it.groupValues[1] }.toList()
            val sumCol = columns.first()
            val critCols = columns.drop(1)
            assertEquals("each criterion range has one criterion", critCols.size, criteria.size)
            val rows = if (sumCol == "I") sheetDebits else sheetInflows
            rows.filter { row ->
                critCols.indices.all { i ->
                    val cell = row[critCols[i]]?.toString().orEmpty()
                    val c = criteria[i]
                    if (c.startsWith("<>")) !cell.equals(c.removePrefix("<>"), ignoreCase = true) else cell.equals(c, ignoreCase = true)
                }
            }.sumOf { (it[sumCol] as Double) }
        }
        val gross = sums.first()
        val refunds = sums.drop(1).sum()
        assertTrue("the formula must clamp at zero", formula.contains("MAX(0,"))
        return maxOf(0.0, gross - refunds)
    }

    @Test
    fun theCurrentMonthFormulaGivesTheAppFigure() {
        val app = LivingBurn.total(appRows, window, emptySet(), appInflows)
        assertEquals(140.0, app, 0.001)
        assertEquals(app, evaluate(DashboardFormulaBuilder.buildCurrentBurnFormula(emptyList())), 0.001)
    }

    @Test
    fun theMonthlySummaryFormulaGivesTheAppFigure() {
        val app = LivingBurn.total(appRows, window, emptySet(), appInflows)
        assertEquals(app, evaluate(DashboardFormulaBuilder.buildMonthlySummaryBurnFormula(6, emptyList())), 0.001)
    }

    @Test
    fun thePreviousMonthFormulaHasTheSameShape() {
        val formula = DashboardFormulaBuilder.buildPrevMonthBurnFormula(emptyList())
        assertEquals(140.0, evaluate(formula), 0.001)
    }

    @Test
    fun aUserExcludedCategoryIsLeftOutByBothSides() {
        val app = LivingBurn.total(appRows, window, setOf("food & drink"), appInflows)
        assertEquals(0.0, app, 0.001) // everything left is excluded and the refund cannot push it below zero
        assertEquals(app, evaluate(DashboardFormulaBuilder.buildCurrentBurnFormula(listOf("Food & Drink"))), 0.001)
    }

    @Test
    fun refundsLargerThanTheBurnClampAtZeroOnBothSides() {
        val big = listOf(inflow("refund", "Refund / Return", 900.0))
        assertEquals(0.0, LivingBurn.total(appRows, window, emptySet(), big), 0.001)
    }
}
