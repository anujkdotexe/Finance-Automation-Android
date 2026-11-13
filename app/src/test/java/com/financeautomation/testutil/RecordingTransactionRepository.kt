package com.financeautomation.testutil

import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.repository.TransactionRepositoryImpl
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Proxy

/**
 * Real [TransactionRepositoryImpl] over recording DAO proxies, for tests that only need to know
 * which transactions were inserted. Inserted rows are available in [inserted].
 */
class RecordingTransactionRepository {
    val inserted = mutableListOf<TransactionEntity>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, onCall: (String, Array<Any?>?) -> Unit): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            onCall(method.name, args)
            when (method.returnType) {
                java.lang.Long.TYPE -> 1L
                java.lang.Integer.TYPE -> 0
                java.lang.Boolean.TYPE -> false
                else -> kotlin.Unit
            }
        } as T

    val repository = TransactionRepositoryImpl(
        transactionDao = proxy(TransactionDao::class.java) { name, args ->
            if (name == "insert") inserted.add(args!![0] as TransactionEntity)
        },
        syncQueueDao = proxy(SyncQueueDao::class.java) { _, _ -> },
        accountingSyncExecutor = createTestAccountingSyncExecutor(),
        ioDispatcher = Dispatchers.Unconfined
    )
}
