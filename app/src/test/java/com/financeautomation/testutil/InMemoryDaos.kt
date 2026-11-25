package com.financeautomation.testutil

import com.financeautomation.core.model.SyncState
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** In-memory transaction table with the same observable behaviour as the Room DAO. */
class InMemoryTransactionDao : TransactionDao {
    val rows = ConcurrentHashMap<String, TransactionEntity>()

    override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flowOf(rows.values.toList())
    override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> =
        flowOf(rows.values.filter { it.reviewState == "NEEDS_REVIEW" && !it.status.equals("Void", ignoreCase = true) })
    override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> = emptyFlow()
    override fun getAccountTransactionCounts(from: Long, to: Long): Flow<List<com.financeautomation.data.local.dao.AccountTransactionCount>> = emptyFlow()
    override suspend fun getById(id: String): TransactionEntity? = rows[id]
    override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? = rows.values.firstOrNull { it.sheetRowIndex == rowIndex }
    override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity> = rows.values.filter { it.sheetRowIndex == rowIndex }
    override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<TransactionEntity> = emptyList()
    override suspend fun insert(transaction: TransactionEntity) { rows[transaction.id] = transaction }
    override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
    override suspend fun update(transaction: TransactionEntity) { rows[transaction.id] = transaction }
    override suspend fun delete(transaction: TransactionEntity) { rows.remove(transaction.id) }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun voidTransaction(id: String, voidTitle: String) {
        rows[id]?.let {
            rows[id] = it.copy(
                totalAmount = 0.0, myShare = 0.0, owedToMe = 0.0, status = "Void",
                syncState = SyncState.VOID.name, merchant = voidTitle, reviewState = "CONFIRMED"
            )
        }
    }
    override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {
        rows[id]?.let { rows[id] = it.copy(syncState = syncState, sheetRowIndex = sheetRowIndex ?: it.sheetRowIndex) }
    }
    override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {
        rows[id]?.let { rows[id] = it.copy(reviewState = reviewState) }
    }
    override suspend fun getByAccount(account: String): List<TransactionEntity> {
        return rows.values.filter { it.account.equals(account, ignoreCase = true) }
    }
    override suspend fun updateAccountName(oldName: String, newName: String): Int {
        var count = 0
        rows.values.filter { it.account.equals(oldName, ignoreCase = true) }.forEach {
            rows[it.id] = it.copy(account = newName)
            count++
        }
        return count
    }
    override suspend fun getByCategory(category: String): List<TransactionEntity> {
        return rows.values.filter { it.category.equals(category, ignoreCase = true) }
    }
    override suspend fun updateCategoryName(oldName: String, newName: String): Int {
        var count = 0
        rows.values.filter { it.category.equals(oldName, ignoreCase = true) }.forEach {
            rows[it.id] = it.copy(category = newName)
            count++
        }
        return count
    }
    override suspend fun updateCompanionName(oldName: String, newName: String): Int {
        var count = 0
        rows.values.filter { it.companions.contains(oldName) }.forEach {
            rows[it.id] = it.copy(companions = it.companions.replace(oldName, newName))
            count++
        }
        return count
    }

    override suspend fun getAllForYear(startMillis: Long, endMillis: Long): List<TransactionEntity> =
        rows.values.filter {
            it.timestamp in startMillis until endMillis &&
                !it.status.equals("Void", ignoreCase = true) &&
                it.reviewState != "NEEDS_REVIEW"
        }.sortedBy { it.timestamp }

    override suspend fun resetSyncStateForRequeue(id: String) {
        rows[id]?.let { rows[id] = it.copy(syncState = SyncState.PENDING.name, sheetRowIndex = null) }
    }
}

/** In-memory inflow table. */
class InMemoryInflowDao : InflowDao {
    val storage = ConcurrentHashMap<String, InflowEntity>()
    override suspend fun insert(inflow: InflowEntity) { storage[inflow.id] = inflow }
    override suspend fun insertAll(inflows: List<InflowEntity>) { inflows.forEach { storage[it.id] = it } }
    override suspend fun update(inflow: InflowEntity) { storage[inflow.id] = inflow }
    override suspend fun getById(id: String): InflowEntity? = storage[id]
    override fun getInflowsByRange(start: Long, end: Long): Flow<List<InflowEntity>> =
        flowOf(storage.values.filter { it.timestamp in start until end })
    override fun getAllInflows(): Flow<List<InflowEntity>> = flowOf(storage.values.toList())
    override suspend fun updateStatus(id: String, status: String) {
        storage[id]?.let { storage[id] = it.copy(status = status) }
    }
    override suspend fun deleteById(id: String) { storage.remove(id) }
    override suspend fun getAllForYear(startMillis: Long, endMillis: Long): List<InflowEntity> =
        storage.values.filter { it.timestamp in startMillis until endMillis && !it.status.equals("VOID", ignoreCase = true) }
            .sortedBy { it.timestamp }
}

/** In-memory payables table. */
class InMemoryPayableDao : PayableDao {
    val storage = ConcurrentHashMap<String, PayableEntity>()
    override fun observeAll(): Flow<List<PayableEntity>> = flowOf(storage.values.toList())
    private fun open(): List<PayableEntity> = storage.values.filter { it.repaymentStatus.lowercase() in setOf("pending", "partial") }
    override fun observePending(): Flow<List<PayableEntity>> = flowOf(open())
    override suspend fun getPending(): List<PayableEntity> = open()
    override suspend fun getAll(): List<PayableEntity> = storage.values.toList()
    override suspend fun getByUuid(uuid: String): PayableEntity? = storage[uuid]
    override suspend fun upsert(payable: PayableEntity) { storage[payable.uuid] = payable }
    override suspend fun upsertAll(payables: List<PayableEntity>) { payables.forEach { storage[it.uuid] = it } }
    override suspend fun deleteByUuid(uuid: String) { storage.remove(uuid) }
    override suspend fun updatePaidByName(oldName: String, newName: String): Int {
        var count = 0
        storage.values.filter { it.paidBy == oldName }.forEach {
            storage[it.uuid] = it.copy(paidBy = newName)
            count++
        }
        return count
    }
    override suspend fun updateAfterNetting(uuid: String, amountIOwe: Double, status: String, dateRepaidSerial: Int?): Int {
        val payable = storage[uuid] ?: return 0
        storage[uuid] = payable.copy(amountIOwe = amountIOwe, repaymentStatus = status, dateRepaidSerial = dateRepaidSerial)
        return 1
    }
    override suspend fun deleteAll() { storage.clear() }
    override suspend fun getAllForYear(
        startSerial: Int,
        endSerial: Int,
        startMillis: Long,
        endMillis: Long
    ): List<PayableEntity> =
        storage.values.filter {
            val serial = it.dateIncurredSerial
            val matchesYear = if (serial != null) {
                serial in startSerial until endSerial
            } else {
                it.updatedAt in startMillis until endMillis
            }
            matchesYear && !it.repaymentStatus.equals("VOID", ignoreCase = true)
        }.sortedWith(compareBy({ it.dateIncurredSerial }, { it.timeSerial }))
}

/** In-memory sync queue that mirrors the Room queries (a FAILED row below the retry cap is still pending). */
class InMemoryQueueDao : SyncQueueDao {
    val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
    private val ids = AtomicLong(1)

    private fun pendingList(maxRetries: Int) = queue.values
        .filter { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING })
    override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.toList())
    override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> =
        flowOf(queue.values.filter { it.status == SyncQueueEntity.STATUS_FAILED || it.retryCount >= 8 })
    override suspend fun insert(item: SyncQueueEntity): Long {
        val id = if (item.id == 0L) ids.getAndIncrement() else item.id
        queue[id] = item.copy(id = id)
        return id
    }
    override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
    override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
    override suspend fun deleteById(id: Long) { queue.remove(id) }
    override suspend fun getById(id: Long): SyncQueueEntity? = queue[id]
    override suspend fun deleteByTransactionId(transactionId: String) {
        queue.values.filter { it.transactionId == transactionId }.forEach { queue.remove(it.id) }
    }
    override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? = pendingList(maxRetries).firstOrNull()
    override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> = pendingList(maxRetries)
    override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> = pendingList(maxRetries)
    override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {
        queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement) }
    }
    override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
        queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount) }
    }
    override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {
        queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount, lastErrorKind = kind, lastErrorDetail = detail) }
    }
    override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {
        queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement, lastErrorKind = kind, lastErrorDetail = detail) }
    }
    override suspend fun resetQueueItem(id: Long) {
        queue[id]?.let { queue[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null) }
    }
    override suspend fun recoverRetryableFailedItems(): Int {
        var count = 0
        queue.values.filter { it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < 8 && it.lastErrorKind in listOf("NO_NETWORK", "RATE_LIMITED", "SERVER_BUSY") }
            .forEach {
                queue[it.id] = it.copy(status = SyncQueueEntity.STATUS_PENDING)
                count++
            }
        return count
    }
    override suspend fun countPendingOperations(opType: String): Int = queue.values.count { it.operationType == opType && it.status == SyncQueueEntity.STATUS_PENDING }
    override suspend fun updatePlanJson(id: Long, planJson: String?) { queue[id]?.let { queue[id] = it.copy(planJson = planJson) } }
    override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {
        queue.values.filter { it.transactionId == oldTransactionId }.forEach { queue[it.id] = it.copy(transactionId = newTransactionId) }
    }
    override suspend fun updatePayloadJsonId(oldId: String, newId: String) {
        queue.values.filter { it.payloadJson.contains(oldId) }.forEach { queue[it.id] = it.copy(payloadJson = it.payloadJson.replace(oldId, newId)) }
    }
    override suspend fun updatePlanJsonId(oldId: String, newId: String) {
        queue.values.filter { it.planJson?.contains(oldId) == true }.forEach { queue[it.id] = it.copy(planJson = it.planJson!!.replace(oldId, newId)) }
    }

    override suspend fun getActiveTransactionIds(): List<String> =
        queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING || it.status == SyncQueueEntity.STATUS_IN_PROGRESS }
            .map { it.transactionId }

    override suspend fun deletePendingForTransactions(transactionIds: List<String>) {
        val idSet = transactionIds.toSet()
        queue.values.filter {
            it.transactionId in idSet &&
            it.status == SyncQueueEntity.STATUS_PENDING &&
            (it.operationType == SyncQueueEntity.OP_INSERT || it.operationType == SyncQueueEntity.OP_UPDATE || it.operationType == SyncQueueEntity.OP_VOID)
        }.forEach { queue.remove(it.id) }
    }
}

/** In-memory carry forward lineage table. */
class InMemoryLineageDao : LineageDao {
    val records = ConcurrentHashMap<String, LineageEntity>()
    override suspend fun insert(entity: LineageEntity) { records[entity.uuid] = entity }
    override suspend fun insertAll(entities: List<LineageEntity>) { entities.forEach { insert(it) } }
    override suspend fun getByUuid(uuid: String): LineageEntity? = records[uuid]
    override suspend fun getByCarriedFrom(carriedFromUuid: String): LineageEntity? =
        records.values.firstOrNull { it.carriedFromUuid == carriedFromUuid }
    override suspend fun getAll(): List<LineageEntity> = records.values.toList()
    override suspend fun updateUuid(oldUuid: String, newUuid: String) {
        records.remove(oldUuid)?.let { records[newUuid] = it.copy(uuid = newUuid) }
    }
    override suspend fun updateCarriedFromUuid(oldUuid: String, newUuid: String) {
        records.values.filter { it.carriedFromUuid == oldUuid }.forEach { records[it.uuid] = it.copy(carriedFromUuid = newUuid) }
    }
    override suspend fun updateRootUuid(oldUuid: String, newUuid: String) {
        records.values.filter { it.rootUuid == oldUuid }.forEach { records[it.uuid] = it.copy(rootUuid = newUuid) }
    }
}
