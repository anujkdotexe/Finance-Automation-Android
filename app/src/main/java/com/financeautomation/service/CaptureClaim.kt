package com.financeautomation.service

import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import kotlinx.coroutines.CancellationException

/** Logs a captured payment at most once, however often its notification button is tapped. */
object CaptureClaim {

    enum class Outcome { LOGGED, SENT_TO_REVIEW, ALREADY_HANDLED, NOT_FOUND, NO_AMOUNT, FAILED }

    /**
     * What the log block did. SENT_TO_REVIEW means the block already moved the capture to Needs Review, so the claim
     * must not put the old state back over it.
     */
    enum class LogResult { LOGGED, SENT_TO_REVIEW, FAILED_RESTORE }

    /**
     * Claims the capture first (WAITING to DONE in one transaction), then calls [log]. A second tap finds it claimed and
     * does nothing. When [log] fails or throws, the previous state is put back so a later tap can try again.
     */
    suspend fun logOnce(
        dao: PendingCaptureDao,
        captureId: String,
        log: suspend (PendingCaptureEntity) -> Boolean
    ): Outcome = logOnceResult(dao, captureId) { capture ->
        if (log(capture)) LogResult.LOGGED else LogResult.FAILED_RESTORE
    }

    /** Same as [logOnce], but the block reports a [LogResult] so a capture it sent to review keeps that state. */
    suspend fun logOnceResult(
        dao: PendingCaptureDao,
        captureId: String,
        log: suspend (PendingCaptureEntity) -> LogResult
    ): Outcome {
        val capture = dao.getById(captureId) ?: return Outcome.NOT_FOUND
        if ((capture.amount ?: 0.0) <= 0.0) return Outcome.NO_AMOUNT
        if (capture.state == PendingCaptureEntity.STATE_DONE || capture.state == PendingCaptureEntity.STATE_DISMISSED) {
            return Outcome.ALREADY_HANDLED
        }
        if (!dao.claimForLogging(captureId)) return Outcome.ALREADY_HANDLED
        val result = try {
            log(capture)
        } catch (e: CancellationException) {
            dao.updateState(captureId, capture.state)
            throw e
        } catch (e: Exception) {
            LogResult.FAILED_RESTORE
        }
        return when (result) {
            LogResult.FAILED_RESTORE -> {
                dao.updateState(captureId, capture.state)
                Outcome.FAILED
            }
            LogResult.SENT_TO_REVIEW -> Outcome.SENT_TO_REVIEW
            LogResult.LOGGED -> {
                dao.updateState(captureId, PendingCaptureEntity.STATE_DONE)
                Outcome.LOGGED
            }
        }
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
