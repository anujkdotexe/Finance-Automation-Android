package com.financeautomation.data

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.InflowType
import com.financeautomation.core.model.RepaymentStatus
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.remote.SheetPayloadMapper
import com.financeautomation.domain.model.InflowTransaction
import com.financeautomation.domain.model.PayableTransaction
import com.financeautomation.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPayloadMapperTest {

    private val sampleDebit = Transaction(
        id = "txn_test_12345",
        timestamp = 1730811600000L,
        dateSerial = 46331,
        timeSerial = 0.54166667,
        account = "SBI",
        merchant = "Swiggy Bangalore",
        tripTag = "Goa Trip",
        category = "Food & Drink",
        totalAmount = 450.0,
        companions = "Personal",
        myShare = 450.0,
        owedToMe = 0.0,
        status = TransactionStatus.SETTLED,
        note = "Dinner note"
    )

    @Test
    fun testMapTransactionToFullRowProduces32Columns() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)
        assertEquals(32, row.size)
    }

    @Test
    fun testTable1ColumnsExactMapping() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)

        // Col A (0): Date serial
        assertEquals(46331, row[0])
        // Col B (1): Time serial
        assertEquals(0.54166667, row[1])
        // Col C (2): Account
        assertEquals("SBI", row[2])
        // Col D (3): Item / Description
        assertEquals("Swiggy Bangalore", row[3])
        // Col E (4): Trip / Event Tag
        assertEquals("Goa Trip", row[4])
        // Col F (5): Category
        assertEquals("Food & Drink", row[5])
        // Col G (6): Total Amount
        assertEquals(450.0, row[6])
        // Col H (7): Companions
        assertEquals("Personal", row[7])
        // Col I (8): My Share
        assertEquals(450.0, row[8])
        // Col J (9): Owed To Me
        assertEquals(0.0, row[9])
        // Col K (10): Status
        assertEquals("Settled", row[10])
        // Col L (11): Expense Note
        assertEquals("Dinner note", row[11])
    }

    @Test
    fun testSpacersAndEmptyTablesForDebit() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)

        // Col M (12): Spacer 1
        assertEquals("", row[12])

        // Table 2 (13..20): Empty
        for (i in 13..20) {
            assertEquals("Table 2 col $i should be empty", "", row[i])
        }

        // Col V (21): Spacer 2
        assertEquals("", row[21])

        // Table 3 (22..28): Empty
        for (i in 22..28) {
            assertEquals("Table 3 col $i should be empty", "", row[i])
        }
    }

    @Test
    fun testHiddenUuidsForDebit() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)

        // Col AD (29): Debit Txn UUID
        assertEquals("txn_test_12345", row[29])
        // Col AE (30): Inflow Txn UUID
        assertEquals("", row[30])
        // Col AF (31): Payable Txn UUID
        assertEquals("", row[31])
    }

    @Test
    fun testPluralSavingsNormalizationInFullRow() {
        val investmentTxn = sampleDebit.copy(category = "investment")
        val row = SheetPayloadMapper.mapTransactionToFullRow(investmentTxn)
        assertEquals("Savings", row[5])
    }

    @Test
    fun testMapTransactionToTable1Returns12Values() {
        val table1 = SheetPayloadMapper.mapTransactionToTable1(sampleDebit)
        assertEquals(12, table1.size)
        assertEquals(46331, table1[0])
        assertEquals("Swiggy Bangalore", table1[3])
        assertEquals(450.0, table1[6])
        assertEquals("Settled", table1[10])
        assertEquals("Dinner note", table1[11])
    }

    @Test
    fun testMapInflowToFullRow() {
        val inflow = InflowTransaction(
            id = "inf_test_999",
            dateSerial = 46331,
            timeSerial = 0.45,
            account = "SBI",
            inflowType = InflowType.SALARY,
            receivedFrom = "Acme Corp",
            reason = "Monthly salary",
            amount = 100000.0,
            linkedDebitUuid = ""
        )
        val row = SheetPayloadMapper.mapInflowToFullRow(inflow)
        assertEquals(32, row.size)

        // Table 1 empty (0..11)
        for (i in 0..11) {
            assertEquals("", row[i])
        }
        // Spacer 1 empty (Col M, 12)
        assertEquals("", row[12])

        // Table 2 populated (Cols N-U, 13..20)
        assertEquals(46331, row[13])
        assertEquals(0.45, row[14])
        assertEquals("SBI", row[15])
        assertEquals("Salary", row[16])
        assertEquals("Acme Corp", row[17])
        assertEquals("Monthly salary", row[18])
        assertEquals(100000.0, row[19])
        assertEquals("", row[20])

        // Spacer 2 empty (Col V, 21)
        assertEquals("", row[21])

        // Table 3 empty (Cols W-AC, 22..28)
        for (i in 22..28) {
            assertEquals("", row[i])
        }

        // Col AD (29) empty
        assertEquals("", row[29])
        // Col AE (30) has inflow UUID
        assertEquals("inf_test_999", row[30])
        // Col AF (31) empty
        assertEquals("", row[31])
    }

    @Test
    fun testMapPayableToFullRow() {
        val payable = PayableTransaction(
            id = "pay_test_888",
            dateIncurredSerial = 46331,
            timeSerial = 0.6,
            paidBy = "Rahul",
            whatFor = "Dinner share",
            amountIOwe = 500.0,
            dateRepaidSerial = null,
            repaymentStatus = RepaymentStatus.PENDING
        )
        val row = SheetPayloadMapper.mapPayableToFullRow(payable)
        assertEquals(32, row.size)

        // Table 1 empty (0..11)
        for (i in 0..11) {
            assertEquals("", row[i])
        }
        // Spacer 1 empty (Col M, 12)
        assertEquals("", row[12])

        // Table 2 empty (Cols N-U, 13..20)
        for (i in 13..20) {
            assertEquals("", row[i])
        }
        // Spacer 2 empty (Col V, 21)
        assertEquals("", row[21])

        // Table 3 (Cols W-AC, 22..28)
        assertEquals(46331, row[22])
        assertEquals(0.6, row[23])
        assertEquals("Rahul", row[24])
        assertEquals("Dinner share", row[25])
        assertEquals(500.0, row[26])
        assertEquals("", row[27])
        assertEquals("Pending", row[28])

        // Hidden System UUIDs (Cols AD-AF, 29..31)
        assertEquals("", row[29])
        assertEquals("", row[30])
        // Col AF (31) has payable UUID
        assertEquals("pay_test_888", row[31])
    }

    @Test
    fun testMapToVoidPayload() {
        val splitTxn = sampleDebit.copy(
            companions = "Rahul",
            myShare = 225.0,
            owedToMe = 225.0,
            status = TransactionStatus.PENDING
        )
        val voidRow = SheetPayloadMapper.mapToVoidPayload(splitTxn)

        assertEquals(32, voidRow.size)
        assertEquals("[Void] Swiggy Bangalore", voidRow[3])
        assertEquals(0.0, voidRow[6]) // Total amount zeroed to prevent gross outflow inflation
        assertEquals(0.0, voidRow[8])   // My Share zeroed
        assertEquals(0.0, voidRow[9])   // Owed To Me zeroed
        assertEquals("Void", voidRow[10]) // Status marked Void
        assertEquals("Dinner note", voidRow[11]) // Note preserved
        assertEquals("txn_test_12345", voidRow[29]) // AD UUID preserved
    }

    @Test
    fun testMapExistingRowToVoidRow() {
        val fullRow = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)
        val voidedRow = SheetPayloadMapper.mapExistingRowToVoidRow(fullRow)

        assertEquals("[Void] Swiggy Bangalore", voidedRow[3])
        assertEquals(0.0, voidedRow[6]) // Col G zeroed
        assertEquals(0.0, voidedRow[8])
        assertEquals(0.0, voidedRow[9])
        assertEquals("Void", voidedRow[10])
    }

    @Test
    fun testMapOriginRolloverUpdate() {
        val pendingTxn = sampleDebit.copy(
            companions = "Rahul",
            myShare = 225.0,
            owedToMe = 225.0,
            status = TransactionStatus.PENDING
        )
        val updated = SheetPayloadMapper.mapOriginRolloverUpdate(pendingTxn)

        assertEquals(0.0, updated.owedToMe, 0.001)
        assertEquals(TransactionStatus.CARRIED_FWD, updated.status)
        assertEquals(225.0, updated.myShare, 0.001) // myShare preserved in origin
    }

    @Test
    fun testMapTargetRolloverNewRow() {
        val pendingTxn = sampleDebit.copy(
            merchant = "Dinner at Olive",
            companions = "Rahul",
            myShare = 225.0,
            owedToMe = 225.0,
            status = TransactionStatus.PENDING
        )
        val targetRow = SheetPayloadMapper.mapTargetRolloverNewRow(
            originTransaction = pendingTxn,
            originMonth = "Nov26",
            newId = "txn_dec_new123"
        )

        assertEquals("txn_dec_new123", targetRow.id)
        assertEquals("[Carried from Nov26] Dinner at Olive", targetRow.merchant)
        assertEquals(0.0, targetRow.myShare, 0.001) // 0 living burn in new month
        assertEquals(225.0, targetRow.owedToMe, 0.001) // receivable carried
        assertEquals(TransactionStatus.PENDING, targetRow.status)
    }

    @Test
    fun testMapExistingRowToVoidRow_32WideRow_parsesWithNote() {
        val row32 = MutableList<Any?>(32) { "" }
        row32[3] = "Lunch"
        row32[6] = 300.0
        row32[8] = 150.0
        row32[9] = 150.0
        row32[10] = "Pending"
        row32[11] = "Lunch with client"
        row32[29] = "txn_debit_32_1"

        val voided = SheetPayloadMapper.mapExistingRowToVoidRow(row32)
        assertEquals(32, voided.size)
        assertEquals("[Void] Lunch", voided[3])
        assertEquals(0.0, voided[6])
        assertEquals("Void", voided[10])
        assertEquals("Lunch with client", voided[11]) // Note preserved
        assertEquals("txn_debit_32_1", voided[29])
    }

    @Test
    fun testMapExistingRowToVoidRow_31WideRow_parsesWithEmptyNote() {
        val row31 = MutableList<Any?>(31) { "" }
        row31[3] = "Dinner"
        row31[6] = 400.0
        row31[8] = 400.0
        row31[9] = 0.0
        row31[10] = "Settled"
        row31[28] = "txn_debit_31_1"

        val voided = SheetPayloadMapper.mapExistingRowToVoidRow(row31)
        assertEquals(31, voided.size)
        assertEquals("[Void] Dinner", voided[3])
        assertEquals(0.0, voided[6])
        assertEquals("Void", voided[10])
        assertEquals("txn_debit_31_1", voided[28]) // AC UUID preserved
    }

    @Test
    fun testMapExistingRowToVoidRow_30WideRow_isRejectedWithIllegalArgumentException() {
        val row30 = MutableList<Any?>(30) { "" }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SheetPayloadMapper.mapExistingRowToVoidRow(row30)
        }
    }
}
