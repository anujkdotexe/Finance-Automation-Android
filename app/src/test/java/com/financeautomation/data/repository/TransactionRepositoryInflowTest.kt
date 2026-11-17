package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.presentation.viewmodel.StubSyncQueueDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class TransactionRepositoryInflowTest {

    private class TestInflowDao : InflowDao {
        val storage = ConcurrentHashMap<String, InflowEntity>()

        override suspend fun insert(inflow: InflowEntity) {
            storage[inflow.id] = inflow
        }

        override suspend fun insertAll(inflows: List<InflowEntity>) {
            inflows.forEach { storage[it.id] = it }
        }

        override suspend fun update(inflow: InflowEntity) {
            storage[inflow.id] = inflow
        }

        override suspend fun getById(id: String): InflowEntity? = storage[id]

        override fun getInflowsByRange(start: Long, end: Long): Flow<List<InflowEntity>> =
            flowOf(storage.values.filter { it.timestamp in start until end })

        override fun getAllInflows(): Flow<List<InflowEntity>> =
            flowOf(storage.values.toList())

        override suspend fun updateStatus(id: String, status: String) {
            storage[id]?.let {
                storage[id] = it.copy(status = status)
            }
        }

        override suspend fun deleteById(id: String) {
            storage.remove(id)
        }

        override suspend fun getAllForYear(startMillis: Long, endMillis: Long): List<InflowEntity> =
            storage.values.filter { it.timestamp in startMillis until endMillis && !it.status.equals("VOID", ignoreCase = true) }
                .sortedBy { it.timestamp }
    }

    private fun mockTransactionDao(): TransactionDao =
        Proxy.newProxyInstance(
            TransactionDao::class.java.classLoader,
            arrayOf(TransactionDao::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Long.TYPE -> 1L
                java.lang.Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        } as TransactionDao

    @Test
    fun testLogCredit_insertsInflowAndSyncQueueItemTogether() = runBlocking {
        val inflowDao = TestInflowDao()
        val syncQueueDao = StubSyncQueueDao()
        val transactionDao = mockTransactionDao()

        val repo = TransactionRepositoryImpl(
            transactionDao = transactionDao,
            syncQueueDao = syncQueueDao,
            inflowDao = inflowDao,
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(syncQueueDao = syncQueueDao, transactionDao = transactionDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        val timestamp = System.currentTimeMillis() - 1000L
        val res = repo.logCredit(
            settler = "Amazon",
            amount = 2500.0,
            explicitRefs = emptyList(),
            account = "SBI",
            inflowType = "Refund / Return",
            reason = "Returned damaged headphones",
            timestamp = timestamp
        )

        assertTrue(res is Result.Success)
        val reqId = (res as Result.Success).data
        assertTrue("reqId must start with cred_", reqId.startsWith("cred_"))

        // Verify InflowEntity inserted
        val storedInflow = inflowDao.storage[reqId]
        assertNotNull("InflowEntity must be stored in InflowDao", storedInflow)
        assertEquals(reqId, storedInflow?.id)
        assertEquals(2500.0, storedInflow?.amount ?: 0.0, 0.001)
        assertEquals("SBI", storedInflow?.account)
        assertEquals("Refund / Return", storedInflow?.inflowType)
        assertEquals("Amazon", storedInflow?.source)
        assertEquals("Returned damaged headphones", storedInflow?.note)
        assertEquals("CONFIRMED", storedInflow?.status)

        // Verify SyncQueueEntity inserted
        val queueItem = syncQueueDao.items.value.find { it.transactionId == reqId }
        assertNotNull("Sync queue item must be inserted", queueItem)
        assertEquals(SyncQueueEntity.OP_LOG_CREDIT, queueItem?.operationType)
        assertTrue(queueItem?.payloadJson?.contains("Returned damaged headphones") == true)
    }

    @Test
    fun testVoidTransaction_marksInflowVoidWhenIdIsCredit() = runBlocking {
        val inflowDao = TestInflowDao()
        val syncQueueDao = StubSyncQueueDao()
        val transactionDao = mockTransactionDao()

        val repo = TransactionRepositoryImpl(
            transactionDao = transactionDao,
            syncQueueDao = syncQueueDao,
            inflowDao = inflowDao,
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(syncQueueDao = syncQueueDao, transactionDao = transactionDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        val reqId = "cred_test123"
        inflowDao.insert(
            InflowEntity(
                id = reqId,
                timestamp = System.currentTimeMillis() - 1000L,
                amount = 1500.0,
                account = "SBI",
                inflowType = "Income / Salary",
                source = "Employer",
                note = "Bonus",
                status = "CONFIRMED"
            )
        )

        val voidRes = repo.voidTransaction(reqId)
        assertTrue(voidRes is Result.Success)

        val updated = inflowDao.getById(reqId)
        assertEquals("VOID", updated?.status)
    }

    @Test
    fun testUndoRecentSave_deletesQueueItemAndVoidsInflow() = runBlocking {
        val inflowDao = TestInflowDao()
        val syncQueueDao = StubSyncQueueDao()
        val transactionDao = mockTransactionDao()

        val repo = TransactionRepositoryImpl(
            transactionDao = transactionDao,
            syncQueueDao = syncQueueDao,
            inflowDao = inflowDao,
            accountingSyncExecutor = com.financeautomation.testutil.createTestAccountingSyncExecutor(syncQueueDao = syncQueueDao, transactionDao = transactionDao),
            ioDispatcher = Dispatchers.Unconfined
        )

        val reqId = "cred_undo456"
        inflowDao.insert(
            InflowEntity(
                id = reqId,
                timestamp = System.currentTimeMillis() - 1000L,
                amount = 800.0,
                account = "SBI",
                inflowType = "Refund / Return",
                source = "Shop",
                note = "Refund",
                status = "CONFIRMED"
            )
        )
        syncQueueDao.insert(
            SyncQueueEntity(
                id = 1L,
                transactionId = reqId,
                operationType = SyncQueueEntity.OP_LOG_CREDIT,
                payloadJson = "{}",
                status = SyncQueueEntity.STATUS_PENDING
            )
        )

        val undoRes = repo.undoRecentSave(reqId)
        assertTrue(undoRes is Result.Success)

        // Queue item deleted
        assertTrue(syncQueueDao.items.value.none { it.transactionId == reqId })
        // Inflow marked VOID
        assertEquals("VOID", inflowDao.getById(reqId)?.status)
    }
}
