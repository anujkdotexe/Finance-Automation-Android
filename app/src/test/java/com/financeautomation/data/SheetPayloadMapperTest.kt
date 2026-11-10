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
        status = TransactionStatus.SETTLED
    )

    @Test
    fun testMapTransactionToFullRowProduces31Columns() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)
        assertEquals(31, row.size)
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
    }

    @Test
    fun testSpacersAndEmptyTablesForDebit() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)

        // Col L (11): Spacer 1
        assertEquals("", row[11])

        // Table 2 (12..19): Empty
        for (i in 12..19) {
            assertEquals("Table 2 col $i should be empty", "", row[i])
        }

        // Col U (20): Spacer 2
        assertEquals("", row[20])

        // Table 3 (21..27): Empty
        for (i in 21..27) {
            assertEquals("Table 3 col $i should be empty", "", row[i])
        }
    }

    @Test
    fun testHiddenUuidsForDebit() {
        val row = SheetPayloadMapper.mapTransactionToFullRow(sampleDebit)

        // Col AC (28): Debit Txn UUID
        assertEquals("txn_test_12345", row[28])
        // Col AD (29): Inflow Txn UUID
        assertEquals("", row[29])
        // Col AE (30): Payable Txn UUID
        assertEquals("", row[30])
    }

    @Test
    fun testPluralSavingsNormalizationInFullRow() {
        val investmentTxn = sampleDebit.copy(category = "investment")
        val row = SheetPayloadMapper.mapTransactionToFullRow(investmentTxn)
        assertEquals("Savings", row[5])
    }

    @Test
    fun testMapTransactionToTable1Returns11Values() {
        val table1 = SheetPayloadMapper.mapTransactionToTable1(sampleDebit)
        assertEquals(11, table1.size)
        assertEquals(46331, table1[0])
        assertEquals("Swiggy Bangalore", table1[3])
        assertEquals(450.0, table1[6])
        assertEquals("Settled", table1[10])
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
        assertEquals(31, row.size)

        // Table 1 empty
        for (i in 0..10) {
            assertEquals("", row[i])
        }
        // Spacer 1 empty
        assertEquals("", row[11])

        // Table 2 populated
        assertEquals(46331, row[12])
        assertEquals(0.45, row[13])
        assertEquals("SBI", row[14])
        assertEquals("Salary", row[15])
        assertEquals("Acme Corp", row[16])
        assertEquals("Monthly salary", row[17])
        assertEquals(100000.0, row[18])
        assertEquals("", row[19])

        // Col AD (29) has inflow UUID
        assertEquals("inf_test_999", row[29])
        // Col AC and AE empty
        assertEquals("", row[28])
        assertEquals("", row[30])
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
        assertEquals(31, row.size)

        // Table 3 (21..27)
        assertEquals(46331, row[21])
        assertEquals(0.6, row[22])
        assertEquals("Rahul", row[23])
        assertEquals("Dinner share", row[24])
        assertEquals(500.0, row[25])
        assertEquals("", row[26])
        assertEquals("Pending", row[27])

        // Col AE (30) has payable UUID
        assertEquals("pay_test_888", row[30])
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

        assertEquals(31, voidRow.size)
        assertEquals("[Void] Swiggy Bangalore", voidRow[3])
        assertEquals(0.0, voidRow[6]) // Total amount zeroed to prevent gross outflow inflation
        assertEquals(0.0, voidRow[8])   // My Share zeroed
        assertEquals(0.0, voidRow[9])   // Owed To Me zeroed
        assertEquals("Void", voidRow[10]) // Status marked Void
        assertEquals("txn_test_12345", voidRow[28]) // AC UUID preserved
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
        assertEquals(TransactionStatus.CARRIED, updated.status)
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
}
