package com.financeautomation.service

import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.testutil.FakePendingCaptureDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** A kill between the claim and the insert must not lose the payment, and a retry must not duplicate it. */
class CaptureRecoveryTest {

    private fun capture(state: String) = PendingCaptureEntity(
        id = "cap1", sourcePackage = "pay", amount = 50.0, payee = "Chai", inferredAccount = null, timestamp = 1000L,
        kind = "DEBIT", flags = "", state = state, notificationKey = "k", contentHash = "h", updatedAt = 1000L
    )

    @Test
    fun theClaimIsAHoldingStateUntilTheExpenseExists() = runBlocking {
        val dao = FakePendingCaptureDao().also { it.captures["cap1"] = capture(PendingCaptureEntity.STATE_WAITING) }
        var stateDuringLog = ""
        CaptureClaim.logOnce(dao, "cap1") { stateDuringLog = dao.captures["cap1"]!!.state; true }
        assertEquals(PendingCaptureEntity.STATE_CLAIMED, stateDuringLog)
        assertEquals(PendingCaptureEntity.STATE_DONE, dao.captures["cap1"]!!.state)
    }

    @Test
    fun aClaimedCaptureWithoutAnExpenseGoesBackToWaitingAtStartup() = runBlocking {
        val dao = FakePendingCaptureDao().also { it.captures["cap1"] = capture(PendingCaptureEntity.STATE_CLAIMED) }
        CaptureClaim.recoverClaimed(dao) { false }
        assertEquals(PendingCaptureEntity.STATE_WAITING, dao.captures["cap1"]!!.state)
    }

    @Test
    fun aClaimedCaptureWhoseExpenseWasInsertedIsDone() = runBlocking {
        val dao = FakePendingCaptureDao().also { it.captures["cap1"] = capture(PendingCaptureEntity.STATE_CLAIMED) }
        CaptureClaim.recoverClaimed(dao) { id -> id == CaptureClaim.expenseIdFor("cap1") }
        assertEquals(PendingCaptureEntity.STATE_DONE, dao.captures["cap1"]!!.state)
    }

    @Test
    fun theExpenseIdIsDerivedFromTheCaptureSoARetryOrASecondPathCannotDuplicateIt() {
        assertEquals(CaptureClaim.expenseIdFor("cap1"), CaptureClaim.expenseIdFor("cap1"))
        assertEquals("txn_cap1", CaptureClaim.expenseIdFor("cap1"))
        assertEquals("txn_ab12", CaptureClaim.expenseIdFor("ab-12"))
    }

    @Test
    fun aSecondClaimOnAClaimedCaptureIsRefused() = runBlocking {
        val dao = FakePendingCaptureDao().also { it.captures["cap1"] = capture(PendingCaptureEntity.STATE_CLAIMED) }
        assertEquals(CaptureClaim.Outcome.ALREADY_HANDLED, CaptureClaim.logOnce(dao, "cap1") { true })
    }

}
