package com.financeautomation.presentation.viewmodel

import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Shared in-memory SyncQueueDao for view model tests that only need the queue flows.
 */
class StubSyncQueueDao(initial: List<SyncQueueEntity> = emptyList()) : SyncQueueDao {
    val items = MutableStateFlow(initial)

    fun add(item: SyncQueueEntity) {
        items.value = items.value + item
    }

    override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> =
        items.map { l -> l.filter { it.status == SyncQueueEntity.STATUS_PENDING } }
    override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = items
    override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> =
        items.map { l -> l.filter { it.status == SyncQueueEntity.STATUS_FAILED || it.retryCount >= 8 } }
    override suspend fun insert(item: SyncQueueEntity): Long { items.value = items.value + item; return item.id }
    override suspend fun update(item: SyncQueueEntity) { items.value = items.value.map { if (it.id == item.id) item else it } }
    override suspend fun delete(item: SyncQueueEntity) { items.value = items.value.filter { it.id != item.id } }
    override suspend fun deleteById(id: Long) { items.value = items.value.filter { it.id != id } }
    override suspend fun getById(id: Long): SyncQueueEntity? = items.value.find { it.id == id }
    override suspend fun deleteByTransactionId(transactionId: String) {
        items.value = items.value.filter { it.transactionId != transactionId }
    }
    override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = null
    override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = emptyList()
    override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = emptyList()
    override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {}
    override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {}
    override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {}
    override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {}
    override suspend fun resetQueueItem(id: Long) {}
    override suspend fun recoverRetryableFailedItems(): Int = 0
    override suspend fun countPendingOperations(opType: String): Int = 0
    override suspend fun updatePlanJson(id: Long, planJson: String?) {}
    override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {}
    override suspend fun updatePayloadJsonId(oldId: String, newId: String) {}
    override suspend fun updatePlanJsonId(oldId: String, newId: String) {}
    override suspend fun getActiveTransactionIds(): List<String> =
        items.value.filter { it.status == SyncQueueEntity.STATUS_PENDING || it.status == SyncQueueEntity.STATUS_IN_PROGRESS }
            .map { it.transactionId }
    override suspend fun deletePendingForTransactions(transactionIds: List<String>) {
        val idSet = transactionIds.toSet()
        items.value = items.value.filterNot {
            it.transactionId in idSet &&
            it.status == SyncQueueEntity.STATUS_PENDING &&
            (it.operationType == SyncQueueEntity.OP_INSERT || it.operationType == SyncQueueEntity.OP_UPDATE || it.operationType == SyncQueueEntity.OP_VOID)
        }
    }
}
