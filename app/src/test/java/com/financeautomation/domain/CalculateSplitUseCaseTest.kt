package com.financeautomation.domain

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalculateSplitUseCaseTest {

    private lateinit var useCase: CalculateSplitUseCase

    @Before
    fun setUp() {
        useCase = CalculateSplitUseCase()
    }

    @Test
    fun testJustMeSplitMath() {
        val split = useCase.calculateJustMe(540.00)
        assertEquals(540.00, split.totalAmount, 0.001)
        assertEquals(540.00, split.myShare, 0.001)
        assertEquals(0.00, split.owedToMe, 0.001)
        assertEquals("Personal", split.companionsText)
        assertEquals(TransactionStatus.PERSONAL, split.status)
        assertEquals(SplitMode.JUST_ME, split.splitMode)
    }

    @Test
    fun testSplitEquallyWithTwoCompanions() {
        // Total 600 across user + 2 companions = 3 heads
        val companions = listOf("Alice", "Bob")
        val split = useCase.calculateSplitEqually(600.00, companions)

        assertEquals(600.00, split.totalAmount, 0.001)
        assertEquals(200.00, split.myShare, 0.001)
        assertEquals(400.00, split.owedToMe, 0.001)
        assertEquals("Alice, Bob", split.companionsText)
        assertEquals(TransactionStatus.PENDING, split.status)
        assertEquals(SplitMode.SPLIT_EQUALLY, split.splitMode)
    }

    @Test
    fun testSplitEquallyEmptyCompanionsDefaultsToJustMe() {
        val split = useCase.calculateSplitEqually(450.00, emptyList())
        assertEquals(450.00, split.myShare, 0.001)
        assertEquals(0.00, split.owedToMe, 0.001)
        assertEquals("Personal", split.companionsText)
        assertEquals(TransactionStatus.PERSONAL, split.status)
    }

    @Test
    fun testCustomSplitPartialShare() {
        val split = useCase.calculateCustomSplit(1000.00, 350.00, listOf("Charlie"))
        assertEquals(1000.00, split.totalAmount, 0.001)
        assertEquals(350.00, split.myShare, 0.001)
        assertEquals(650.00, split.owedToMe, 0.001)
        assertEquals(TransactionStatus.PENDING, split.status)
        assertEquals(SplitMode.CUSTOM, split.splitMode)
    }

    @Test
    fun testCustomSplitFullShareIsPersonal() {
        val split = useCase.calculateCustomSplit(1000.00, 1000.00, listOf("Charlie"))
        assertEquals(1000.00, split.myShare, 0.001)
        assertEquals(0.00, split.owedToMe, 0.001)
        assertEquals(TransactionStatus.PERSONAL, split.status)
    }

    @Test
    fun testCustomSplitZeroShareClamps() {
        val split = useCase.calculateCustomSplit(600.00, 0.00, listOf("Dave"))
        assertEquals(0.00, split.myShare, 0.001)
        assertEquals(600.00, split.owedToMe, 0.001)
        assertEquals(TransactionStatus.PENDING, split.status)
        assertEquals(SplitMode.CUSTOM, split.splitMode)
    }

    @Test
    fun testSplitEquallyWithThreeCompanions() {
        // Total 1200 across user + 3 companions = 4 heads
        val companions = listOf("Alice", "Bob", "Charlie")
        val split = useCase.calculateSplitEqually(1200.00, companions)

        assertEquals(1200.00, split.totalAmount, 0.001)
        assertEquals(300.00, split.myShare, 0.001)
        assertEquals(900.00, split.owedToMe, 0.001)
        assertEquals("Alice, Bob, Charlie", split.companionsText)
        assertEquals(TransactionStatus.PENDING, split.status)
        assertEquals(SplitMode.SPLIT_EQUALLY, split.splitMode)
    }

    @Test
    fun testCustomSplitNegativeShareClamps() {
        val split = useCase.calculateCustomSplit(500.00, -50.00, listOf("Dave"))
        assertEquals(0.00, split.myShare, 0.001)
        assertEquals(500.00, split.owedToMe, 0.001)
    }

    @Test
    fun testCustomSplitShareExceedingTotalClamps() {
        val split = useCase.calculateCustomSplit(500.00, 550.00, listOf("Dave"))
        assertEquals(500.00, split.myShare, 0.001)
        assertEquals(0.00, split.owedToMe, 0.001)
    }

    @Test
    fun testDebtRolloverZeroesColJAndSetsCarried() {
        val original = Transaction(
            id = "txn_nov26_01",
            timestamp = System.currentTimeMillis(),
            dateSerial = 46331,
            timeSerial = 0.5,
            account = "SBI XX1234",
            merchant = "Goa Resort",
            category = "Trip/Travel",
            totalAmount = 9000.00,
            companions = "Alice, Bob",
            myShare = 3000.00,
            owedToMe = 6000.00,
            status = TransactionStatus.PENDING
        )

        val (updatedOrigin, targetTxn) = useCase.calculateRollover(original, "Nov26", 46361, 0.1)

        // Verify origin row: Col J zeroed, status Carried
        assertEquals(0.00, updatedOrigin.owedToMe, 0.001)
        assertEquals(TransactionStatus.CARRIED_FWD, updatedOrigin.status)

        // Verify target row: fresh UUID, description prefixed, Col J carries debt, status Pending
        assertNotEquals(original.id, targetTxn.id)
        assertTrue(targetTxn.id.startsWith("txn_carried_"))
        assertEquals("[Carried from Nov26] Goa Resort", targetTxn.merchant)
        assertEquals(6000.00, targetTxn.owedToMe, 0.001)
        assertEquals(0.00, targetTxn.myShare, 0.001)
        assertEquals(TransactionStatus.PENDING, targetTxn.status)
    }

    @Test
    fun testInPlaceVoidZeroesSharesAndPrefixesDescription() {
        val original = Transaction(
            id = "txn_void_01",
            timestamp = System.currentTimeMillis(),
            dateSerial = 46331,
            timeSerial = 0.5,
            account = "SBI XX1234",
            merchant = "Wrong Item",
            category = "Shopping",
            totalAmount = 800.00,
            companions = "Personal",
            myShare = 800.00,
            owedToMe = 0.00,
            status = TransactionStatus.SETTLED
        )

        val voided = useCase.calculateInPlaceVoid(original)
        assertEquals(0.00, voided.myShare, 0.001)
        assertEquals(0.00, voided.owedToMe, 0.001)
        assertEquals(TransactionStatus.VOID, voided.status)
        assertEquals("[Void] Wrong Item", voided.merchant)
        assertEquals(original.id, voided.id)
    }

    @Test
    fun testStrictPluralSavingsKeywordRule() {
        assertEquals("Savings", useCase.normalizeCategory("Savings"))
        assertEquals("Savings", useCase.normalizeCategory("savings"))
        assertEquals("Savings", useCase.normalizeCategory("investment"))
        assertEquals("Savings", useCase.normalizeCategory("SIP"))
        assertEquals("Savings", useCase.normalizeCategory("mutual fund"))
    }

    @Test
    fun testSingularSavingNormalizesToPluralSavings() {
        assertEquals("Savings", useCase.normalizeCategory("Saving"))
    }

    @Test
    fun testCalculateRolloverUsesInjectedClock() {
        val fixedMillis = 1773489600000L
        val fixedClock = java.time.Clock.fixed(
            java.time.Instant.ofEpochMilli(fixedMillis),
            java.time.ZoneId.of("Asia/Kolkata")
        )
        val customUseCase = CalculateSplitUseCase(fixedClock)
        val original = Transaction(
            id = "txn_123",
            timestamp = 1000L,
            dateSerial = 46000,
            timeSerial = 0.5,
            account = "HDFC",
            merchant = "Dinner",
            category = "Food",
            totalAmount = 1000.0,
            companions = "Alice",
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING
        )
        val (_, carried) = customUseCase.calculateRollover(original, "Feb26", 46030, 0.5)
        assertEquals(fixedMillis, carried.timestamp)
    }

    @Test
    fun testFirstWriteDebitRowIncludesGuardedNoteInColumnL() {
        val useCase = CalculateSplitUseCase()
        val planNormal = useCase(
            amount = 100.0,
            merchant = "Coffee",
            category = "Food & Drink",
            account = "SBI",
            splitMode = com.financeautomation.core.model.SplitMode.JUST_ME,
            companions = emptyList(),
            dateSerial = 46000,
            timeSerial = 0.5,
            tabName = "Nov26",
            note = "Met Alice"
        )
        val cellsNormal = planNormal.appends.first().cells
        assertEquals("Debit row must have exactly 12 cells (Columns A to L)", 12, cellsNormal.size)
        assertEquals("Column L must contain plain text note", "Met Alice", cellsNormal[11])

        val planFormula = useCase(
            amount = 100.0,
            merchant = "Coffee",
            category = "Food & Drink",
            account = "SBI",
            splitMode = com.financeautomation.core.model.SplitMode.JUST_ME,
            companions = emptyList(),
            dateSerial = 46000,
            timeSerial = 0.5,
            tabName = "Nov26",
            note = "=A1+B1"
        )
        val cellsFormula = planFormula.appends.first().cells
        assertEquals("Debit row must have exactly 12 cells (Columns A to L)", 12, cellsFormula.size)
        assertEquals("Column L note starting with '=' must be escaped with single quote", "'=A1+B1", cellsFormula[11])
    }
}
