package com.financeautomation.data.local

import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.domain.model.Inflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class InflowDaoTest {

    private class InMemoryInflowDao : InflowDao {
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
            flowOf(storage.values.filter { it.timestamp in start until end }.sortedByDescending { it.timestamp })

        override fun getAllInflows(): Flow<List<InflowEntity>> =
            flowOf(storage.values.sortedByDescending { it.timestamp })

        override suspend fun updateStatus(id: String, status: String) {
            storage[id]?.let {
                storage[id] = it.copy(status = status)
            }
        }

        override suspend fun deleteById(id: String) {
            storage.remove(id)
        }
    }

    @Test
    fun testInflowEntity_domainMappingRoundTrip() {
        val original = Inflow(
            id = "inf_map_1",
            timestamp = 1700000000000L,
            amount = 7500.50,
            account = "SBI",
            inflowType = "Income / Salary",
            source = "Acme Corp",
            note = "November Salary",
            createdAt = 1700000001000L,
            status = "CONFIRMED"
        )

        val entity = InflowEntity.fromDomain(original)
        assertEquals(original.id, entity.id)
        assertEquals(original.timestamp, entity.timestamp)
        assertEquals(original.amount, entity.amount, 0.001)
        assertEquals(original.account, entity.account)
        assertEquals(original.inflowType, entity.inflowType)
        assertEquals(original.source, entity.source)
        assertEquals(original.note, entity.note)
        assertEquals(original.createdAt, entity.createdAt)
        assertEquals(original.status, entity.status)

        val mappedBack = entity.toDomain()
        assertEquals(original, mappedBack)
    }

    @Test
    fun testInflowDao_insertAndGetById() = runBlocking {
        val dao = InMemoryInflowDao()
        val inflow = InflowEntity(
            id = "inf_test_1",
            timestamp = 1000L,
            amount = 2500.0,
            account = "HDFC",
            inflowType = "Refund / Return",
            source = "Flipkart",
            note = "Returned item",
            createdAt = 1000L,
            status = "CONFIRMED"
        )
        dao.insert(inflow)

        val retrieved = dao.getById("inf_test_1")
        assertNotNull(retrieved)
        assertEquals(2500.0, retrieved?.amount ?: 0.0, 0.001)
        assertEquals("Flipkart", retrieved?.source)
    }

    @Test
    fun testInflowDao_getInflowsByRange_filtersCorrectly() = runBlocking {
        val dao = InMemoryInflowDao()
        dao.insert(InflowEntity("inf_jan", 1000L, 500.0, "SBI", "Income", "S1", "N1", 1000L, "CONFIRMED"))
        dao.insert(InflowEntity("inf_feb", 2000L, 700.0, "SBI", "Income", "S2", "N2", 2000L, "CONFIRMED"))
        dao.insert(InflowEntity("inf_mar", 3000L, 900.0, "SBI", "Income", "S3", "N3", 3000L, "CONFIRMED"))

        val inWindow = dao.getInflowsByRange(1500L, 2500L).first()
        assertEquals(1, inWindow.size)
        assertEquals("inf_feb", inWindow.first().id)
    }

    @Test
    fun testInflowDao_updateStatusAndDeletion() = runBlocking {
        val dao = InMemoryInflowDao()
        dao.insert(InflowEntity("inf_void_test", 1000L, 300.0, "SBI", "Income", "S", "N", 1000L, "CONFIRMED"))

        dao.updateStatus("inf_void_test", "VOID")
        assertEquals("VOID", dao.getById("inf_void_test")?.status)

        dao.deleteById("inf_void_test")
        assertNull(dao.getById("inf_void_test"))
    }
}

