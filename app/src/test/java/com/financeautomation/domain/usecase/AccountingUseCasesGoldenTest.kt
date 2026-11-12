package com.financeautomation.domain.usecase

import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.TableType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AccountingUseCasesGoldenTest {

    private val nettingUseCase = NettingUseCase()
    private val repaymentWaterfallUseCase = RepaymentWaterfallUseCase(nettingUseCase)
    private val carryForwardUseCase = CarryForwardUseCase()
    private val fixedClock = Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneOffset.UTC)
    private val yearCloseUseCase = YearCloseUseCase(fixedClock)

    @Test
    fun testNettingUseCase_offsetsDebitsAgainstPayables() {
        val payables = listOf(
            LedgerPayable(
                tab = "Nov26",
                row = 5,
                uuid = "pay_1",
                dateIncurredSerial = 46340,
                timeSerial = 0.5,
                paidBy = "Charlie",
                whatFor = "Coffee",
                amountOwed = 100.0,
                dateRepaidSerial = null,
                status = "Pending"
            )
        )

        val result = nettingUseCase.nettingOnDebit(
            companions = "Charlie",
            owed = 60.0,
            dateSerial = 46340,
            payables = payables
        )

        assertEquals(0.0, result.netOwed, 0.001)
        assertEquals(60.0, result.offsetTotal, 0.001)
        // 3 edits: AA (dateRepaidSerial), Z (amountOwed), AB (status Partial)
        assertEquals(3, result.edits.size)
    }

    @Test
    fun testRepaymentWaterfallUseCase_repayingPayable() {
        val payables = listOf(
            LedgerPayable(
                tab = "Nov26",
                row = 5,
                uuid = "pay_charlie",
                dateIncurredSerial = 46340,
                timeSerial = 0.5,
                paidBy = "Charlie",
                whatFor = "Dinner",
                amountOwed = 150.0,
                dateRepaidSerial = null,
                status = "Pending"
            )
        )

        val plan = repaymentWaterfallUseCase(
            creditor = "Charlie",
            amount = 150.0,
            explicitRef = null,
            dateSerial = 46345,
            timeSerial = 0.5,
            tabName = "Nov26",
            allPayables = payables
        )

        assertEquals(0.0, plan.excess, 0.001)
        val statusEdit = plan.edits.find { it.column == "AB" }
        assertNotNull(statusEdit)
        assertEquals("Repaid", statusEdit!!.value)
    }

    @Test
    fun testCarryForwardUseCase_rollsOverDebtsAndPayables() {
        val debits = listOf(
            LedgerDebit(
                tab = "Nov26",
                row = 4,
                uuid = "deb_nov_1",
                dateSerial = 46330,
                timeSerial = 0.5,
                account = "Cash",
                item = "Groceries",
                tripTag = "",
                category = "Food & Drink",
                total = 200.0,
                companions = "Bob",
                myShare = 100.0,
                owed = 100.0,
                status = "Pending"
            )
        )

        val result = carryForwardUseCase(
            targetTab = "Dec26",
            sourceTab = "Nov26",
            targetDebits = emptyList(),
            targetPayables = emptyList(),
            sourceDebits = debits,
            sourcePayables = emptyList()
        )

        // Source debit must be marked Carried Fwd
        val carriedEdit = result.plan.edits.find { it.tab == "Nov26" && it.column == "K" }
        assertNotNull(carriedEdit)
        assertEquals("Carried Fwd", carriedEdit!!.value)

        // Target row appended to Table 1
        val append = result.plan.appends.find { it.tab == "Dec26" && it.table == TableType.DEBIT }
        assertNotNull(append)
        assertEquals("[Carried from Nov26] Groceries", append!!.cells[3])
        assertEquals(100.0, append.cells[9] as Double, 0.001)
    }

    @Test
    fun testYearCloseUseCase_compilesUnresolvedBalances() {
        val debits = listOf(
            LedgerDebit(
                tab = "Dec26",
                row = 5,
                uuid = "deb_dec_5",
                dateSerial = 46360,
                timeSerial = 0.5,
                account = "Cash",
                item = "Skiing",
                tripTag = "Manali",
                category = "Trip/Travel",
                total = 500.0,
                companions = "Alice",
                myShare = 250.0,
                owed = 250.0,
                status = "Pending"
            )
        )

        val statement = yearCloseUseCase(
            year = 2026,
            allDebits = debits,
            allPayables = emptyList()
        )

        assertEquals(2026, statement.year)
        assertEquals(1, statement.receivableCount)
        assertEquals(0, statement.payableCount)
        assertTrue(statement.jsonContent.contains("Skiing"))
        assertTrue(statement.jsonContent.contains("250.0"))
    }
}
