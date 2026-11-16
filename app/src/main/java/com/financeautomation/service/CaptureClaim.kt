package com.financeautomation.service

import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import kotlinx.coroutines.CancellationException

/** Logs a captured payment at most once, however often its notification button is tapped. */
object CaptureClaim {

    enum class Outcome { LOGGED, ALREADY_HANDLED, NOT_FOUND, NO_AMOUNT, FAILED }

    /**
     * Claims the capture first (WAITING to DONE in one transaction), then calls [log]. A second tap finds it claimed and
     * does nothing. When [log] fails or throws, the previous state is put back so a later tap can try again.
     */
    suspend fun logOnce(
        dao: PendingCaptureDao,
        captureId: String,
        log: suspend (PendingCaptureEntity) -> Boolean
    ): Outcome {
        val capture = dao.getById(captureId) ?: return Outcome.NOT_FOUND
        if ((capture.amount ?: 0.0) <= 0.0) return Outcome.NO_AMOUNT
        if (!dao.claimForLogging(captureId)) return Outcome.ALREADY_HANDLED
        val ok = try {
            log(capture)
        } catch (e: CancellationException) {
            dao.updateState(captureId, capture.state)
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            dao.updateState(captureId, capture.state)
            return Outcome.FAILED
        }
        dao.updateState(captureId, PendingCaptureEntity.STATE_DONE)
        return Outcome.LOGGED
    }

    /** The expense id derived from the capture: a retry, or the other save path, writes the same row instead of a second one. */
    fun expenseIdFor(captureId: String): String = "txn_" + captureId.replace("-", "")

    /**
     * At start: a capture left CLAIMED means the process died between the claim and the insert. If its expense exists it
     * is DONE, otherwise it goes back to WAITING so the payment is not lost.
     */
    suspend fun recoverClaimed(dao: PendingCaptureDao, expenseExists: suspend (String) -> Boolean) {
        for (capture in dao.getByState(PendingCaptureEntity.STATE_CLAIMED)) {
            val next = if (expenseExists(expenseIdFor(capture.id))) PendingCaptureEntity.STATE_DONE else PendingCaptureEntity.STATE_WAITING
            dao.updateState(capture.id, next)
        }
    }
}
