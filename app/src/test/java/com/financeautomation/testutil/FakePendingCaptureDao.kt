package com.financeautomation.testutil

import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** In-memory [PendingCaptureDao] for unit tests. */
class FakePendingCaptureDao : PendingCaptureDao {
    val captures = mutableMapOf<String, PendingCaptureEntity>()
    override suspend fun insert(capture: PendingCaptureEntity) { captures[capture.id] = capture }
    override suspend fun update(capture: PendingCaptureEntity) { captures[capture.id] = capture }
    override suspend fun getById(id: String): PendingCaptureEntity? = captures[id]
    override suspend fun getByNotificationKey(key: String): PendingCaptureEntity? =
        captures.values.lastOrNull { it.notificationKey == key }
    override suspend fun getByContentHash(hash: String, since: Long): PendingCaptureEntity? =
        captures.values.lastOrNull { it.contentHash == hash && it.timestamp >= since }
    override suspend fun getByState(state: String): List<PendingCaptureEntity> = captures.values.filter { it.state == state }
    override fun getWaitingCapturesFlow(): Flow<List<PendingCaptureEntity>> =
        flowOf(captures.values.filter { it.state == "WAITING" })
    override fun getNeedsReviewCapturesFlow(): Flow<List<PendingCaptureEntity>> =
        flowOf(captures.values.filter { it.state in listOf("NEEDS_REVIEW", "DISMISSED", "WAITING", "SHOWN") })
    override fun getNewAccountCapturesFlow(): Flow<List<PendingCaptureEntity>> =
        flowOf(captures.values.filter { it.flags.contains("NEW_ACCOUNT") && it.state != "DONE" })
    override suspend fun getWaitingCaptures(): List<PendingCaptureEntity> = captures.values.filter { it.state == "WAITING" }
    override suspend fun getExpiredWaitingCaptures(cutoffTime: Long): List<PendingCaptureEntity> =
        captures.values.filter { it.state == "WAITING" && it.timestamp < cutoffTime }
    override suspend fun getAwaitingSuccessCaptures(): List<PendingCaptureEntity> =
        captures.values.filter { it.state == "AWAITING_SUCCESS" }
    override suspend fun findSimilarByAmountAndTime(amount: Double, minTime: Long, maxTime: Long): List<PendingCaptureEntity> =
        captures.values.filter { it.amount == amount && it.timestamp in minTime..maxTime }
    override suspend fun updateState(id: String, state: String, updatedAt: Long) {
        captures[id]?.let { captures[id] = it.copy(state = state, updatedAt = updatedAt) }
    }
    override suspend fun markDismissedIfOpen(id: String, updatedAt: Long): Int {
        val cap = captures[id] ?: return 0
        if (cap.state != "WAITING" && cap.state != "SHOWN") return 0
        captures[id] = cap.copy(state = "DISMISSED", updatedAt = updatedAt)
        return 1
    }
    override suspend fun resetShownToWaiting(updatedAt: Long): Int {
        val shown = captures.values.filter { it.state == "SHOWN" }
        shown.forEach { cap ->
            captures[cap.id] = cap.copy(state = "WAITING", updatedAt = updatedAt)
        }
        return shown.size
    }
    override suspend fun deleteById(id: String) { captures.remove(id) }
    override suspend fun pruneOldCompletedCaptures(cutoffTime: Long) {}
}
