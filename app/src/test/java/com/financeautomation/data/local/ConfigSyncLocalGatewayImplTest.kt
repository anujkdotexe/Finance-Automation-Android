package com.financeautomation.data.local

import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.testutil.InMemoryAccountDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** ConfigSyncLocalGatewayImpl on in-memory account storage. */
class ConfigSyncLocalGatewayImplTest {

    private fun gateway(dao: InMemoryAccountDao) =
        ConfigSyncLocalGatewayImpl(accountDao = dao, ioDispatcher = Dispatchers.Unconfined)

    private fun seed(dao: InMemoryAccountDao) = runBlocking {
        dao.insert(AccountEntity(id = "acc_cash", name = "Cash", displayOrder = 0, updatedAt = 1L))
        dao.insert(AccountEntity(id = "acc_sbi", name = "SBI", isDefault = true, displayOrder = 2, updatedAt = 1L))
    }

    @Test
    fun deletingDefaultAccountFallsBackToCash() = runBlocking {
        val dao = InMemoryAccountDao()
        seed(dao)

        gateway(dao).applyRemoteAccount("SBI", null, isDefault = false, isArchived = false, displayOrder = 2, updatedAt = 5L, deleted = true)

        assertEquals(true, dao.accounts.getValue("acc_sbi").isArchived)
        assertEquals("Cash is the only default", listOf("acc_cash"), dao.accounts.values.filter { it.isDefault }.map { it.id })
    }

    @Test
    fun deletingANonDefaultAccountKeepsTheDefault() = runBlocking {
        val dao = InMemoryAccountDao()
        seed(dao)
        dao.insert(AccountEntity(id = "acc_hdfc", name = "HDFC", updatedAt = 1L))

        gateway(dao).applyRemoteAccount("HDFC", null, isDefault = false, isArchived = false, displayOrder = 3, updatedAt = 5L, deleted = true)

        assertEquals(listOf("acc_sbi"), dao.accounts.values.filter { it.isDefault }.map { it.id })
    }
}
