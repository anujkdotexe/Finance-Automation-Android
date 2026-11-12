package com.financeautomation.data

import com.financeautomation.data.local.dao.AccountTransactionCount
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.repository.AccountRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class AccountTransactionCountsTest {

    /** A TransactionDao that only answers the counts query and records the window it was asked for. */
    private class CountsDao(private val rows: List<AccountTransactionCount>) {
        var lastFrom: Long? = null
        var lastTo: Long? = null

        val dao: TransactionDao = Proxy.newProxyInstance(
            TransactionDao::class.java.classLoader,
            arrayOf(TransactionDao::class.java)
        ) { _, method, args ->
            if (method.name == "getAccountTransactionCounts") {
                lastFrom = args[0] as Long
                lastTo = args[1] as Long
                flowOf(rows)
            } else {
                throw UnsupportedOperationException(method.name)
            }
        } as TransactionDao
    }

    @Test
    fun testCountsAreMappedByAccountName() = runBlocking {
        val counts = CountsDao(listOf(AccountTransactionCount("HDFC", 12), AccountTransactionCount("Cash", 3)))
        val repo = AccountRepositoryImpl(
            transactionDaoProvider = { counts.dao },
            ioDispatcher = Dispatchers.Unconfined
        )

        val map = repo.getAccountTransactionCounts(1_000L, 2_000L).first()

        assertEquals(mapOf("HDFC" to 12, "Cash" to 3), map)
        assertEquals(1_000L, counts.lastFrom)
        assertEquals(2_000L, counts.lastTo)
    }

    @Test
    fun testNoTransactionDaoGivesEmptyMap() = runBlocking {
        val repo = AccountRepositoryImpl(ioDispatcher = Dispatchers.Unconfined)
        assertEquals(emptyMap<String, Int>(), repo.getAccountTransactionCounts(0L, 1L).first())
    }
}
