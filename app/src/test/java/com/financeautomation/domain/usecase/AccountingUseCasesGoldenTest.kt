package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.SheetSchemaConstants
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
    private val voidTransactionUseCase = VoidTransactionUseCase()
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
        // 3 edits: AB (dateRepaidSerial), AA (amountOwed), AC (status Partial)
        assertEquals(3, result.edits.size)
        val dateEdit = result.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_DATE_REPAID }
        assertNotNull(dateEdit)
        assertEquals(46340, dateEdit!!.value)
        val owedEdit = result.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE }
        assertNotNull(owedEdit)
        assertEquals(40.0, owedEdit!!.value as Double, 0.001)
        val statusEdit = result.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS }
        assertNotNull(statusEdit)
        assertEquals("Partial", statusEdit!!.value)
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
        val statusEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS }
        assertNotNull(statusEdit)
        assertEquals("Repaid", statusEdit!!.value)
        val dateEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_DATE_REPAID }
        assertNotNull(dateEdit)
        assertEquals(46345, dateEdit!!.value)
        val owedEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE }
        assertNotNull(owedEdit)
        assertEquals(0.0, owedEdit!!.value as Double, 0.001)
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

    @Test
    fun test32ColumnLayout_carryForwardModifiesColumnAC() {
        val payable = LedgerPayable(
            tab = "Nov26",
            row = 6,
            uuid = "pay_cf",
            dateIncurredSerial = 46340,
            timeSerial = 0.5,
            paidBy = "Alice",
            whatFor = "Brunch",
            amountOwed = 80.0,
            dateRepaidSerial = null,
            status = "Pending"
        )

        val result = carryForwardUseCase(
            targetTab = "Dec26",
            sourceTab = "Nov26",
            targetDebits = emptyList(),
            targetPayables = emptyList(),
            sourceDebits = emptyList(),
            sourcePayables = listOf(payable)
        )

        val edit = result.plan.edits.find { it.tab == "Nov26" && it.row == 6 }
        assertNotNull("Must generate edit for carried-forward payable", edit)
        assertEquals(SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS, edit!!.column)
        assertEquals("Carried Fwd", edit.value)
    }

    @Test
    fun test32ColumnLayout_voidDebitModifiesColumnsD_G_I_J_K() {
        val debit = LedgerDebit(
            tab = "Nov26",
            row = 10,
            uuid = "deb_void",
            dateSerial = 46340,
            timeSerial = 0.5,
            account = "HDFC",
            item = "Concert Ticket",
            tripTag = "",
            category = "Entertainment",
            total = 1200.0,
            companions = "Dave",
            myShare = 600.0,
            owed = 600.0,
            status = "Pending"
        )

        val plan = voidTransactionUseCase.voidDebit(debit)
        assertEquals(5, plan.edits.size)

        val descEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_DEBIT_DESCRIPTION }
        assertNotNull(descEdit)
        assertEquals("[Void] Concert Ticket", descEdit!!.value)

        val totalEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_DEBIT_TOTAL_AMOUNT }
        assertNotNull(totalEdit)
        assertEquals(0.0, totalEdit!!.value as Double, 0.001)

        val shareEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_DEBIT_MY_SHARE }
        assertNotNull(shareEdit)
        assertEquals(0.0, shareEdit!!.value as Double, 0.001)

        val owedEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME }
        assertNotNull(owedEdit)
        assertEquals(0.0, owedEdit!!.value as Double, 0.001)

        val statusEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_DEBIT_STATUS }
        assertNotNull(statusEdit)
        assertEquals("Void", statusEdit!!.value)
    }

    @Test
    fun test32ColumnLayout_voidInflowModifiesColumnsS_T() {
        val plan = voidTransactionUseCase.voidInflow(
            tab = "Nov26",
            row = 7,
            currentReason = "Freelance payment"
        )

        assertEquals(2, plan.edits.size)

        val reasonEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_INFLOW_REASON }
        assertNotNull(reasonEdit)
        assertEquals("[Void] Freelance payment", reasonEdit!!.value)

        val amountEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_INFLOW_AMOUNT }
        assertNotNull(amountEdit)
        assertEquals(0.0, amountEdit!!.value as Double, 0.001)
    }

    @Test
    fun test32ColumnLayout_voidPayableModifiesColumnsZ_AA_AC() {
        val payable = LedgerPayable(
            tab = "Nov26",
            row = 8,
            uuid = "pay_void",
            dateIncurredSerial = 46340,
            timeSerial = 0.5,
            paidBy = "Frank",
            whatFor = "Taxi fare",
            amountOwed = 250.0,
            dateRepaidSerial = null,
            status = "Pending"
        )

        val plan = voidTransactionUseCase.voidPayable(payable)
        assertEquals(3, plan.edits.size)

        val whatForEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_WHAT_FOR }
        assertNotNull(whatForEdit)
        assertEquals("[Void] Taxi fare", whatForEdit!!.value)

        val owedEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE }
        assertNotNull(owedEdit)
        assertEquals(0.0, owedEdit!!.value as Double, 0.001)

        val statusEdit = plan.edits.find { it.column == SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS }
        assertNotNull(statusEdit)
        assertEquals("Void", statusEdit!!.value)
    }
}
