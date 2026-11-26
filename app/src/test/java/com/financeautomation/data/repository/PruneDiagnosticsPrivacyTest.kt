package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Removing entries the sheet no longer holds must leave only counts in Diagnostics: never an amount, a name or a
 * description. Real repository and prune path, scripted Sheets server, invented data.
 */
class PruneDiagnosticsPrivacyTest {

    private val noon = ZonedDateTime.of(2026, 11, 15, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()

    @Test
    fun pruningAReceivableAndADebtRecordsNoAmountNameOrDescription() = runBlocking {
        val h = SyncHarness(tabs = listOf("Nov26"))
        h.now = noon
        h.preferences[PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID] = "test_sheet_id"
        val receivable = h.expense("txn_shared", merchant = "Zephyrmart", amount = 9000.0).copy(
            companions = "Quillon", myShare = 4679.0, owedToMe = 4321.0, status = TransactionStatus.PENDING,
            reviewState = ReviewState.CONFIRMED, syncState = SyncState.SYNCED, sheetRowIndex = 4
        )
        h.tDao.insert(TransactionEntity.fromDomain(receivable))
        h.pDao.upsert(
            PayableEntity(
                uuid = "pay_leftover", dateIncurredSerial = DateSerialUtils.toDateSerial(noon - 60_000L),
                timeSerial = 0.5, paidBy = "Quillon", whatFor = "Zephyrmart", amountIOwe = 8765.0,
                dateRepaidSerial = null, repaymentStatus = "Pending", tabName = "", sheetRowIndex = null
            )
        )
        val empty = listOf("A4:L", "AD4:AD", "N4:U", "AE4:AE", "W4:AC", "AF4:AF").joinToString(",") {
            """{"range":"'Nov26'!$it","majorDimension":"ROWS","values":[]}"""
        }
        h.server.customRead = { call -> if (call.url.contains("values:batchGet")) """{"valueRanges":[$empty]}""" else null }
        Diagnostics.clear()

        val outcome = h.repo.reconcileWithSheetOutcome()

        assertTrue("reconcile must succeed: $outcome", outcome is Result.Success)
        assertEquals("the receivable and the leftover debt are both removed", 2, (outcome as Result.Success).data.pruned)
        val logged = Diagnostics.getRecords().joinToString("\n") { it.reason }
        assertTrue("the removal itself is still counted", logged.contains("SHEET_PRUNE removed=2"))
        listOf("4321", "4679", "9000", "8765", "Zephyrmart", "Quillon").forEach {
            assertFalse("Diagnostics must not hold $it: $logged", logged.contains(it))
        }
    }
}
