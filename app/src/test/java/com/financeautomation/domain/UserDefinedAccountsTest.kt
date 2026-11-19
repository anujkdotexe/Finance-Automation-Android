package com.financeautomation.domain

import com.financeautomation.testutil.InMemoryAccountDao
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
        assertEquals("Other", success.inferredAccount)
        assertEquals("A/c XX9988", success.detectedAccountHint)
        // Transaction account MUST be the safe defaultAccount ("Other"), NOT the unconfigured pattern
        assertEquals("Other", success.transaction.account)
    }

    @Test
    fun testParseNotification_unknownAccountPattern_savesTransactionUnderDefaultAccountAndKeepsDetectedInCapture() {
        val configured = listOf("ICICI Bank", "Cash", "Other")
        val defaultAccount = "ICICI Bank"

        val result = parseUseCase(
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            title = "Paid to Swiggy",
            text = "Paid Rs 420 from A/c XX4821 to Swiggy",
            configuredAccounts = configured,
            defaultAccount = defaultAccount
        )

        assertTrue(result is NotificationParseResult.Success)
        val success = result as NotificationParseResult.Success
        // Flags indicate new account detected
        assertEquals("NEW_ACCOUNT", success.flags)
        // Transaction is saved under defaultAccount so only configured accounts reach the sheet
        assertEquals("ICICI Bank", success.transaction.account)
        // Inferred account is safe defaultAccount
        assertEquals(defaultAccount, success.inferredAccount)
        assertEquals("A/c XX4821", success.detectedAccountHint)
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
        val dao = InMemoryAccountDao()
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
        val dao = InMemoryAccountDao()
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

    @Test
    fun testAccountRepository_deletingDefaultAccount_resetsDefaultToOther() = runBlocking {
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", "Cash", null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", "Other", null, isDefault = false, isArchived = false, displayOrder = 1))
        dao.insert(AccountEntity("acc_user", "My HDFC", null, isDefault = true, isArchived = false, displayOrder = 2))

        val repo = AccountRepositoryImpl(accountDao = dao)

        assertEquals("My HDFC", repo.getDefaultAccount()?.name)

        repo.deleteAccount("acc_user")
        assertEquals(null, dao.getById("acc_user"))

        val def = repo.getDefaultAccount()
        assertNotNull("Default account must not be null after deleting previous default", def)
        assertEquals("Other", def!!.name)
        assertTrue(def.isDefault)
    }

    @Test
    fun testAccountRepository_archivingDefaultAccount_resetsDefaultToOther() = runBlocking {
        val dao = InMemoryAccountDao()
        dao.insert(AccountEntity("acc_cash", "Cash", null, isDefault = false, isArchived = false, displayOrder = 0))
        dao.insert(AccountEntity("acc_other", "Other", null, isDefault = false, isArchived = false, displayOrder = 1))
        dao.insert(AccountEntity("acc_user", "My HDFC", null, isDefault = true, isArchived = false, displayOrder = 2))

        val repo = AccountRepositoryImpl(accountDao = dao)

        assertEquals("My HDFC", repo.getDefaultAccount()?.name)

        repo.archiveAccount("acc_user")
        assertTrue(dao.getById("acc_user")!!.isArchived)
        assertFalse(dao.getById("acc_user")!!.isDefault)

        val def = repo.getDefaultAccount()
        assertNotNull("Default account must not be null after archiving previous default", def)
        assertEquals("Other", def!!.name)
        assertTrue(def.isDefault)
    }
}

