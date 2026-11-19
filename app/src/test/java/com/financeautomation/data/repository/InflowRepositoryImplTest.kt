package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.presentation.viewmodel.StubSyncQueueDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class InflowRepositoryImplTest {

    private class TestInflowDao : InflowDao {
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

    private val timestamp = 1794387699000L

    private fun inflow(id: String = "cred_abc123") = InflowEntity(
        id = id,
        timestamp = timestamp,
        amount = 50000.0,
        account = "SBI",
        inflowType = "Income / Salary",
        source = "Employer",
        note = "Salary",
        status = "CONFIRMED"
    )

    private class Fixture {
        val dao = TestInflowDao()
        val queue = StubSyncQueueDao()
        var triggers = 0
        var transactions = 0
        val repo = InflowRepositoryImpl(
            inflowDaoProvider = { dao },
            ioDispatcher = Dispatchers.Unconfined,
            syncQueueDao = queue,
            transactionRunner = { block -> transactions++; block() }
        ).also { it.onTriggerSync = { triggers++ } }
    }

    @Test
    fun voidInflowSetsStatusAndQueuesOneVoidInSameTransaction() = runBlocking {
        val f = Fixture()
        f.dao.insert(inflow())

        val result = f.repo.voidInflow("cred_abc123")

        assertTrue(result is Result.Success)
        assertEquals("VOID", f.dao.storage.getValue("cred_abc123").status)
        val items = f.queue.items.value
        assertEquals(1, items.size)
        assertEquals(SyncQueueEntity.OP_VOID_INFLOW, items[0].operationType)
        assertEquals("cred_abc123", items[0].transactionId)
        val payload = AccountingRequestJsonAdapter.voidInflowRequestFromJson(items[0].payloadJson)
        assertEquals("cred_abc123", payload.inflowId)
        assertEquals(timestamp, payload.timestamp)
        assertEquals("status and queue are written inside one transaction", 1, f.transactions)
        assertEquals(1, f.triggers)
    }

    @Test
    fun voidingTwiceIsANoOpThatSucceeds() = runBlocking {
        val f = Fixture()
        f.dao.insert(inflow())

        assertTrue(f.repo.voidInflow("cred_abc123") is Result.Success)
        assertTrue(f.repo.voidInflow("cred_abc123") is Result.Success)

        assertEquals("VOID", f.dao.storage.getValue("cred_abc123").status)
        assertEquals(1, f.queue.items.value.size)
        assertEquals(1, f.triggers)
    }

    @Test
    fun unknownIdReturnsErrorAndQueuesNothing() = runBlocking {
        val f = Fixture()

        val result = f.repo.voidInflow("missing")

        assertTrue(result is Result.Error)
        assertTrue(f.queue.items.value.isEmpty())
        assertEquals(0, f.triggers)
    }

    @Test
    fun voidOnlyTouchesTheNamedInflow() = runBlocking {
        val f = Fixture()
        f.dao.insert(inflow("cred_one"))
        f.dao.insert(inflow("cred_two"))

        f.repo.voidInflow("cred_one")

        assertEquals("VOID", f.dao.storage.getValue("cred_one").status)
        assertEquals("CONFIRMED", f.dao.storage.getValue("cred_two").status)
    }
}
