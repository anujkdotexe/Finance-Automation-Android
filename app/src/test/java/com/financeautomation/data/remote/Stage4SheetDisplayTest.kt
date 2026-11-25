package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.usecase.CarryForwardUseCase
import com.financeautomation.domain.usecase.SettlementWaterfallUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage4SheetDisplayTest {

    @Test
    fun testStage4b_inflowColumnU_writesHumanReadableSheetRowReference() {
        val useCase = SettlementWaterfallUseCase()
        val debit = LedgerDebit(
            tab = "Nov26",
            row = 5,
            uuid = "txn_debit_12345",
            total = 1000.0,
            owed = 500.0,
            status = "Pending",
            companions = "Ananya",
            dateSerial = 46340,
            timeSerial = 0.5,
            account = "HDFC",
            item = "Dinner",
            category = "Food & Drink",
            myShare = 500.0,
            tripTag = null
        )

        val plan = useCase(
            settler = "Ananya",
            amount = 500.0,
            creditDateSerial = 46340,
            creditTimeSerial = 0.5,
            account = "HDFC",
            inflowType = "Friend Settlement",
            reason = "Dinner share",
            tabName = "Nov26",
            allDebits = listOf(debit)
        )

        val inflowAppend = plan.appends.find { it.table == TableType.INFLOW }
        assertNotNull("Expected inflow append", inflowAppend)
        // Column U is at index 7 of inflowCells (Linked Debit Row)
        val linkedDebitCell = inflowAppend!!.cells[7].toString()
        assertEquals("Column U must be human-readable tab and row reference", "Nov26!Row5", linkedDebitCell)

        // Verify resolveReference can parse both human-readable, decorated, and legacy UUID references
        val resolvedFromHuman = useCase.invoke(
            settler = "Ananya",
            amount = 500.0,
            explicitRefs = listOf("Nov26!Row5"),
            creditDateSerial = 46340,
            creditTimeSerial = 0.5,
            account = "HDFC",
            inflowType = "Friend Settlement",
            reason = "Dinner share",
            tabName = "Nov26",
            allDebits = listOf(debit)
        )
        assertEquals(1, resolvedFromHuman.settled.size)
        assertEquals("Nov26", resolvedFromHuman.settled[0].tab)
        assertEquals(5, resolvedFromHuman.settled[0].row)

        val resolvedFromDecorated = useCase.invoke(
            settler = "Ananya",
            amount = 500.0,
            explicitRefs = listOf("Nov26!Row5 (txn_debit_12345)"),
            creditDateSerial = 46340,
            creditTimeSerial = 0.5,
            account = "HDFC",
            inflowType = "Friend Settlement",
            reason = "Dinner share",
            tabName = "Nov26",
            allDebits = listOf(debit)
        )
        assertEquals(1, resolvedFromDecorated.settled.size)
        assertEquals("Nov26", resolvedFromDecorated.settled[0].tab)

        val resolvedFromLegacyUuid = useCase.invoke(
            settler = "Ananya",
            amount = 500.0,
            explicitRefs = listOf("txn_debit_12345"),
            creditDateSerial = 46340,
            creditTimeSerial = 0.5,
            account = "HDFC",
            inflowType = "Friend Settlement",
            reason = "Dinner share",
            tabName = "Nov26",
            allDebits = listOf(debit)
        )
        assertEquals(1, resolvedFromLegacyUuid.settled.size)
    }

    @Test
    fun testStage4c_dashboardRequests_explicitlyClearsL26StatusHeader() {
        val requests = SheetProvisioner.buildDashboardRequests(dashboardSheetId = 1, year = 2026)

        // Look for updateCells request at rowIndex 25, colIndex 11 (L26)
        @Suppress("UNCHECKED_CAST")
        val l26ClearReq = requests.mapNotNull { it["updateCells"] as? Map<String, Any?> }
            .firstOrNull {
                val r = it["range"] as? Map<*, *>
                r?.get("startRowIndex") == 25 && r?.get("startColumnIndex") == 11
            }

        assertNotNull("Must include explicit cell update request to clear L26", l26ClearReq)
        @Suppress("UNCHECKED_CAST")
        val cellVal = (((l26ClearReq!!["rows"] as List<Map<String, Any?>>).first()["values"] as List<Map<String, Any?>>).first()["userEnteredValue"]) as Map<String, Any?>
        assertEquals("L26 value must be empty string to wipe out leftover Status header", "", cellVal["stringValue"])
    }

    @Test
    fun testStage4a_repairMonthTabSchema_includesDateFormatOnColumnAB() {
        val requests = SheetProvisioner.buildRepairMonthTabSchemaRequests(sheetId = 2, tabTitle = "Nov26", rowCount = 1000)

        // Verify request formatting Column AB (col index 27) as DATE with pattern dd/mm/yyyy
        @Suppress("UNCHECKED_CAST")
        val abFormatReq = requests.mapNotNull { it["repeatCell"] as? Map<String, Any?> }
            .firstOrNull {
                val r = it["range"] as? Map<*, *>
                r?.get("startColumnIndex") == SheetSchemaConstants.COL_INDEX_PAYABLE_DATE_REPAID &&
                        r?.get("endColumnIndex") == SheetSchemaConstants.COL_INDEX_PAYABLE_DATE_REPAID + 1
            }

        assertNotNull("Repair requests must include number formatting for Column AB (Date Repaid)", abFormatReq)
        @Suppress("UNCHECKED_CAST")
        val cell = abFormatReq!!["cell"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val userEnteredFormat = cell["userEnteredFormat"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val numberFormat = userEnteredFormat["numberFormat"] as Map<String, Any?>
        assertEquals("DATE", numberFormat["type"])
        assertEquals("dd/mm/yyyy", numberFormat["pattern"])
    }

    @Test
    fun testStage4d_carryForwardEmitsFull12ColumnsForDebits() {
        val carryForwardUseCase = CarryForwardUseCase()
        val debit = LedgerDebit(
            tab = "Nov26",
            row = 4,
            uuid = "txn_src_1",
            total = 800.0,
            owed = 400.0,
            status = "Pending",
            companions = "Karan",
            dateSerial = 46340,
            timeSerial = 0.25,
            account = "ICICI",
            item = "Groceries",
            category = "Groceries",
            myShare = 400.0,
            tripTag = null
        )

        val payable = LedgerPayable(
            tab = "Nov26",
            row = 4,
            uuid = "pay_src_1",
            dateIncurredSerial = 46340,
            timeSerial = 0.25,
            paidBy = "Karan",
            whatFor = "Movie tickets",
            amountOwed = 300.0,
            dateRepaidSerial = null,
            status = "Pending"
        )

        val result = carryForwardUseCase(
            targetTab = "Dec26",
            sourceTab = "Nov26",
            targetDebits = emptyList(),
            targetPayables = emptyList(),
            sourceDebits = listOf(debit),
            sourcePayables = listOf(payable)
        )

        val debitAppend = result.plan.appends.find { it.table == TableType.DEBIT }
        assertNotNull("Expected carried debit append", debitAppend)
        // Must have exactly 12 columns (A to L)
        assertEquals("Carried debit row must have 12 columns including Column L Note", 12, debitAppend!!.cells.size)
        assertEquals("", debitAppend.cells[11]) // Note is empty string
        assertEquals(SheetSchemaConstants.COL_INDEX_EXPENSE_NOTE, 11)

        val payableAppend = result.plan.appends.find { it.table == TableType.PAYABLE }
        assertNotNull("Expected carried payable append", payableAppend)
        assertEquals(7, payableAppend!!.cells.size)
    }
}
