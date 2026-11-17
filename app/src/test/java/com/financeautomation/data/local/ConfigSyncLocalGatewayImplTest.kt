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

    private fun defaults(dao: InMemoryAccountDao) = dao.accounts.values.filter { it.isDefault }.map { it.name }

    @Test
    fun remoteDefaultAccountClearsOldDefault() = runBlocking {
        val dao = InMemoryAccountDao()
        seed(dao)
        dao.insert(AccountEntity(id = "acc_hdfc", name = "HDFC", updatedAt = 1L))

        // Existing account becomes default on another device
        gateway(dao).applyRemoteAccount("HDFC", null, isDefault = true, isArchived = false, displayOrder = 3, updatedAt = 5L, deleted = false)
        assertEquals(listOf("HDFC"), defaults(dao))

        // A brand new account arrives as default
        gateway(dao).applyRemoteAccount("Axis", null, isDefault = true, isArchived = false, displayOrder = 4, updatedAt = 6L, deleted = false)
        assertEquals(listOf("Axis"), defaults(dao))
    }

    @Test
    fun twoDefaultsInSheetKeepsNewest() = runBlocking {
        val dao = InMemoryAccountDao()
        seed(dao)
        val gateway = gateway(dao)

        // The newer row is read first, the older row second: the older one must not take the default back
        gateway.applyRemoteAccount("Axis", null, isDefault = true, isArchived = false, displayOrder = 3, updatedAt = 9L, deleted = false)
        gateway.applyRemoteAccount("ICICI", null, isDefault = true, isArchived = false, displayOrder = 4, updatedAt = 7L, deleted = false)

        assertEquals(listOf("Axis"), defaults(dao))
        assertEquals("the older account is still created, just not default", true, dao.accounts.values.any { it.name == "ICICI" })
    }

    @Test
    fun aNonDefaultRemoteAccountLeavesTheDefaultAlone() = runBlocking {
        val dao = InMemoryAccountDao()
        seed(dao)

        gateway(dao).applyRemoteAccount("Axis", null, isDefault = false, isArchived = false, displayOrder = 3, updatedAt = 9L, deleted = false)

        assertEquals(listOf("SBI"), defaults(dao))
    }
}
