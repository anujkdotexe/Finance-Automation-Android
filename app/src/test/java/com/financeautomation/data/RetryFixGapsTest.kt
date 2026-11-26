package com.financeautomation.data

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.LedgerDebitRow
import com.financeautomation.data.remote.LedgerWorkbookSnapshot
import com.financeautomation.data.remote.MonthTabSnapshot
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.usecase.ChainRow
import com.financeautomation.domain.usecase.StoredPlanReplay
import com.financeautomation.testutil.InMemoryLineageDao
import com.financeautomation.testutil.InMemoryPayableDao
import com.financeautomation.testutil.InMemoryQueueDao
import com.financeautomation.testutil.InMemoryTransactionDao
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryFixGapsTest {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Test
    fun testAlreadyWrittenPopulatesMovedFromAndMarksReceivableCarriedForward() = runBlocking {
        // Plan editing debit row 5 in Oct26
        val plan = AccountingPlan(
            edits = listOf(
                CellEdit(
                    tab = "Oct26",
                    row = 5,
                    column = SheetSchemaConstants.COL_LETTER_DEBIT_STATUS,
                    value = "Settled",
                    uuid = "txn_split_1",
                    before = "Pending"
                ),
                CellEdit(
                    tab = "Oct26",
                    row = 5,
                    column = SheetSchemaConstants.COL_LETTER_DEBIT_OWED_TO_ME,
                    value = 0.0,
                    uuid = "txn_split_1",
                    before = 500.0
                )
            )
        )

        // Sheet already shows this row as "Carried Fwd" with amount 0.0 (already written by carry forward)
        val debitsOnSheet = listOf(
            ChainRow(
                uuid = "txn_split_1",
                tab = "Oct26",
                row = 5,
                text = "Dinner",
                party = "Riya",
                dateSerial = 46338,
                timeSerial = 0.5,
                amount = 0.0,
                status = "Carried Fwd"
            )
        )

        val outcome = StoredPlanReplay.check(plan, debitsOnSheet, emptyList())
        assertTrue("Outcome must be AlreadyWritten, got $outcome", outcome is StoredPlanReplay.Outcome.AlreadyWritten)
        val alreadyWritten = outcome as StoredPlanReplay.Outcome.AlreadyWritten
        assertEquals("movedFrom must contain the carried row", 1, alreadyWritten.movedFrom.size)
        assertEquals("txn_split_1", alreadyWritten.movedFrom[0].uuid)

        // Verify alignLocalAfterReplay in AccountingSyncExecutor updates Room
        val tDao = InMemoryTransactionDao()
        val qDao = InMemoryQueueDao()
        val pDao = InMemoryPayableDao()
        val lDao = InMemoryLineageDao()

        // Seed Room with original pending transaction
        tDao.insert(
            TransactionEntity(
                id = "txn_split_1",
                timestamp = 1794387699000L,
                dateSerial = 46338,
                timeSerial = 0.5,
                account = "SBI",
                merchant = "Dinner",
                tripTag = "",
                category = "Food",
                totalAmount = 1000.0,
                companions = "Riya",
                myShare = 500.0,
                owedToMe = 500.0,
                status = TransactionStatus.PENDING.value,
                reviewState = ReviewState.CONFIRMED.name,
                syncState = SyncState.SYNCED.name,
                sheetRowIndex = 5,
                note = "Split dinner",
                sourcePackage = "manual",
                isPossibleDuplicate = false
            )
        )

        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"range":"'Sheet'!A1","values":[]}""".toResponseBody(jsonMediaType))
                .build()
        }
        val okHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val sheets = GoogleSheetsClient(okHttp, tokenProvider)
        val drive = GoogleDriveClient(okHttp, tokenProvider)

        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = drive,
            syncQueueDao = qDao,
            lineageDao = lDao,
            transactionDao = tDao,
            payableDao = pDao
        )

        val snapshot = LedgerWorkbookSnapshot(
            spreadsheetId = "test_sheet_id",
            monthTabs = listOf(
                MonthTabSnapshot(
                    tabName = "Oct26",
                    sheetId = 1,
                    debits = listOf(
                        LedgerDebitRow(
                            rowNumber = 5,
                            dateSerial = 46338,
                            timeSerial = 0.5,
                            account = "SBI",
                            description = "Dinner",
                            tripTag = "",
                            category = "Food",
                            totalAmount = 1000.0,
                            companions = "Riya",
                            myShare = 500.0,
                            owedToMe = 0.0,
                            status = "Carried Fwd",
                            note = "",
                            uuid = "txn_split_1"
                        )
                    ),
                    payables = emptyList()
                )
            ),
            isComplete = true
        )

        executor.alignLocalAfterReplay(snapshot, alreadyWritten.local, alreadyWritten.movedFrom)

        val localTxn = tDao.getById("txn_split_1")
        assertNotNull(localTxn)
        assertEquals("Local entity must be marked Carried Fwd", "Carried Fwd", localTxn!!.status)
    }

    @Test
    fun testSavedPlanRetriedAcrossYearReturnsUnprovableWhenDebtMovedToNextYear() {
        val plan = AccountingPlan(
            edits = listOf(
                CellEdit(
                    tab = "Dec26",
                    row = 6,
                    column = SheetSchemaConstants.COL_LETTER_PAYABLE_AMOUNT_I_OWE,
                    value = 0.0,
                    uuid = "pay_carried_99",
                    before = 500.0
                ),
                CellEdit(
                    tab = "Dec26",
                    row = 6,
                    column = SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS,
                    value = "Repaid",
                    uuid = "pay_carried_99",
                    before = "Pending"
                )
            )
        )

        // Dec26 row was carried forward at year end
        val payablesOnSheet = listOf(
            ChainRow(
                uuid = "pay_carried_99",
                tab = "Dec26",
                row = 6,
                text = "Tickets",
                party = "Charlie",
                dateSerial = 46380,
                timeSerial = 0.4,
                amount = 500.0,
                status = "Carried Fwd"
            )
        )

        // Snapshot only has 2026 tabs (Dec26), no live copy in 2026
        val outcome = StoredPlanReplay.check(plan, emptyList(), payablesOnSheet)
        assertEquals(StoredPlanReplay.Outcome.Unprovable, outcome)
    }
}
