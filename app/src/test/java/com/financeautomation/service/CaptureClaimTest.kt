package com.financeautomation.service

import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.testutil.FakePendingCaptureDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** A capture is logged once, however many times its notification button is tapped. */
class CaptureClaimTest {

    private fun capture(amount: Double? = 120.0, state: String = PendingCaptureEntity.STATE_WAITING) = PendingCaptureEntity(
        id = "cap1", sourcePackage = "com.example.pay", amount = amount, payee = "Chai", inferredAccount = null,
        timestamp = 1000L, kind = "DEBIT", flags = "", state = state, notificationKey = "k1", contentHash = "h", updatedAt = 1000L
    )

    private fun dao(c: PendingCaptureEntity) = FakePendingCaptureDao().also { it.captures[c.id] = c }

    @Test
    fun twoTapsLogOnceAndTheSecondIsIgnored() = runBlocking {
        val dao = dao(capture())
        var logged = 0
        val log: suspend (PendingCaptureEntity) -> Boolean = { logged++; true }
        assertEquals(CaptureClaim.Outcome.LOGGED, CaptureClaim.logOnce(dao, "cap1", log))
        assertEquals(CaptureClaim.Outcome.ALREADY_HANDLED, CaptureClaim.logOnce(dao, "cap1", log))
        assertEquals(1, logged)
        assertEquals(PendingCaptureEntity.STATE_DONE, dao.captures["cap1"]!!.state)
    }

    @Test
    fun aFailedLogPutsTheCaptureBackSoTheNextTapCanTryAgain() = runBlocking {
        val dao = dao(capture())
        var attempts = 0
        val flaky: suspend (PendingCaptureEntity) -> Boolean = { attempts++; attempts > 1 }
        assertEquals(CaptureClaim.Outcome.FAILED, CaptureClaim.logOnce(dao, "cap1", flaky))
        assertEquals(PendingCaptureEntity.STATE_WAITING, dao.captures["cap1"]!!.state)
        assertEquals(CaptureClaim.Outcome.LOGGED, CaptureClaim.logOnce(dao, "cap1", flaky))
    }

    @Test
    fun aLogThatThrowsAlsoReleasesTheClaim() = runBlocking {
        val dao = dao(capture())
        val boom: suspend (PendingCaptureEntity) -> Boolean = { throw IllegalStateException("db") }
        assertEquals(CaptureClaim.Outcome.FAILED, CaptureClaim.logOnce(dao, "cap1", boom))
        assertEquals(PendingCaptureEntity.STATE_WAITING, dao.captures["cap1"]!!.state)
    }

    @Test
    fun finishedMissingAndEmptyCapturesAreNotLogged() = runBlocking {
        var logged = 0
        val log: suspend (PendingCaptureEntity) -> Boolean = { logged++; true }
        assertEquals(CaptureClaim.Outcome.ALREADY_HANDLED, CaptureClaim.logOnce(dao(capture(state = PendingCaptureEntity.STATE_DONE)), "cap1", log))
        assertEquals(CaptureClaim.Outcome.ALREADY_HANDLED, CaptureClaim.logOnce(dao(capture(state = PendingCaptureEntity.STATE_DISMISSED)), "cap1", log))
        assertEquals(CaptureClaim.Outcome.NOT_FOUND, CaptureClaim.logOnce(FakePendingCaptureDao(), "cap1", log))
        assertEquals(CaptureClaim.Outcome.NO_AMOUNT, CaptureClaim.logOnce(dao(capture(amount = 0.0)), "cap1", log))
        assertEquals(0, logged)
    }

}
