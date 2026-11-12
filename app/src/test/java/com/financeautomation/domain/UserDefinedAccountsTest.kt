package com.financeautomation.domain

import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.repository.AccountRepositoryImpl
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.usecase.NotificationParseResult
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDefinedAccountsTest {

    private val parseUseCase = ParseNotificationUseCase(ownPackageName = "com.financeautomation")

    private class FakeAccountDao : AccountDao {
        val accounts = mutableMapOf<String, AccountEntity>()
        override fun getAccountsFlow(): Flow<List<AccountEntity>> = flowOf(accounts.values.toList())
        override fun getActiveAccountsFlow(): Flow<List<AccountEntity>> = flowOf(accounts.values.filter { !it.isArchived })
        override suspend fun getAllAccounts(): List<AccountEntity> = accounts.values.toList()
        override suspend fun getActiveAccounts(): List<AccountEntity> = accounts.values.filter { !it.isArchived }
        override suspend fun getById(id: String): AccountEntity? = accounts[id]
        override suspend fun getByName(name: String): AccountEntity? = accounts.values.find { it.name.equals(name, ignoreCase = true) }
        override suspend fun getDefaultAccount(): AccountEntity? = accounts.values.find { it.isDefault }
        override suspend fun insert(account: AccountEntity) { accounts[account.id] = account }
        override suspend fun insertAll(accounts: List<AccountEntity>) { accounts.forEach { insert(it) } }
        override suspend fun update(account: AccountEntity) { accounts[account.id] = account }
        override suspend fun clearDefaultFlag() {
            accounts.replaceAll { _, a -> a.copy(isDefault = false) }
        }
        override suspend fun setDefaultFlag(id: String) {
            accounts[id]?.let { accounts[id] = it.copy(isDefault = true) }
        }
        override suspend fun archiveAccount(id: String, updatedAt: Long) {
            accounts[id]?.let { accounts[id] = it.copy(isArchived = true, updatedAt = updatedAt) }
        }
        override suspend fun unarchiveAccount(id: String, updatedAt: Long) {
            accounts[id]?.let { accounts[id] = it.copy(isArchived = false, updatedAt = updatedAt) }
        }
        override suspend fun delete(account: AccountEntity) { accounts.remove(account.id) }
        override suspend fun deleteById(id: String) { accounts.remove(id) }
        override suspend fun updateDisplayOrder(id: String, displayOrder: Int, updatedAt: Long) {
            accounts[id]?.let { accounts[id] = it.copy(displayOrder = displayOrder, updatedAt = updatedAt) }
        }
        override suspend fun updateName(id: String, name: String, updatedAt: Long) {
            accounts[id]?.let { accounts[id] = it.copy(name = name, updatedAt = updatedAt) }
        }
        override suspend fun getDirtyAccounts(): List<AccountEntity> = emptyList()
        override suspend fun markClean(ids: List<String>) {}
        override suspend fun count(): Int = accounts.size
    }

    @Test
    fun testParseNotification_matchesConfiguredAccountByNameAndDigits() {
        val configured = listOf("HDFC Bank XX4821", "ICICI Bank")

        // Matches known account by digits
        val (acc1, isNew1) = ParseNotificationUseCase.inferAccountWithFlag(
            text = "Debited Rs 450 from A/c XX4821 at Starbucks",
            configuredAccounts = configured,
            defaultAccount = "Other"
        )
        assertEquals("HDFC Bank XX4821", acc1)
        assertFalse(isNew1)

        // Matches known account by bank name
        val (acc2, isNew2) = ParseNotificationUseCase.inferAccountWithFlag(
            text = "Paid Rs 300 using ICICI Bank to Swiggy",
            configuredAccounts = configured,
            defaultAccount = "Other"
        )
        assertEquals("ICICI Bank", acc2)
        assertFalse(isNew2)
    }

    @Test
    fun testParseNotification_unknownAccountPattern_flagsNewAccount() {
        val configured = listOf("Cash", "Other")

        val result = parseUseCase(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Paid to Starbucks",
            text = "Paid Rs 250 from A/c XX9988 to Starbucks",
            configuredAccounts = configured,
            defaultAccount = "Other"
        )

        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        assertEquals("NEW_ACCOUNT", success.flags)
        assertTrue(success.inferredAccount.contains("9988"))
    }

    @Test
    fun testParseNotification_noAccountMention_fallsBackToDefaultAccount() {
        val configured = listOf("HDFC Bank")

        val (acc, isNew) = ParseNotificationUseCase.inferAccountWithFlag(
            text = "Paid Rs 150 to Chai Point",
            configuredAccounts = configured,
            defaultAccount = "HDFC Bank"
        )
        assertEquals("HDFC Bank", acc)
        assertFalse(isNew)
    }

    @Test
    fun testAccountRepository_firstUserAddedAccountBecomesDefault() = runBlocking {
        val dao = FakeAccountDao()
        // Pre-seed Cash and Other as in FinanceDatabase
        dao.insert(AccountEntity("acc_cash", "Cash", null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", "Other", null, isDefault = false, isArchived = false, displayOrder = 1))

        val repo = AccountRepositoryImpl(accountDao = dao)

        // Insert first user account
        repo.insertAccount(Account("acc_user_1", "HDFC Bank XX1234", isDefault = false))

        val inserted = dao.getById("acc_user_1")
        assertNotNull(inserted)
        assertTrue("First user-added account must automatically become default", inserted!!.isDefault)
    }

    @Test
    fun testAccountRepository_cashAndOtherCannotBeDeleted() = runBlocking {
        val dao = FakeAccountDao()
        dao.insert(AccountEntity("acc_cash", "Cash", null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", "Other", null, isDefault = false, isArchived = false, displayOrder = 1))
        dao.insert(AccountEntity("acc_user", "My HDFC", null, isDefault = true, isArchived = false, displayOrder = 2))

        val repo = AccountRepositoryImpl(accountDao = dao)

        repo.deleteAccount("acc_cash")
        repo.deleteAccount("acc_other")

        assertNotNull("Cash must not be deleted", dao.getById("acc_cash"))
        assertNotNull("Other must not be deleted", dao.getById("acc_other"))

        // User account can be deleted
        repo.deleteAccount("acc_user")
        assertEquals(null, dao.getById("acc_user"))
    }
}

