package com.financeautomation.data.account

import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Signing in with another Google account must not mix its ledger with the previous account's. */
class AccountSwitchGuardTest {

    private class FakeStore(private val h: SyncHarness, private val events: MutableList<String>) : AccountDataStore {
        var wipes = 0
        override suspend fun pendingCount() = h.qDao.queue.values.count { it.operationType != SyncQueueEntity.OP_CONFIG_PUSH }
        override suspend fun hasLocalData() = h.tDao.rows.isNotEmpty() || h.qDao.queue.isNotEmpty()
        override suspend fun wipe() {
            wipes++
            events += "wipe"
            h.tDao.rows.clear(); h.iDao.storage.clear(); h.qDao.queue.clear()
        }
    }

    private val events = mutableListOf<String>()
    private val h = SyncHarness()
    private val store = FakeStore(h, events)
    private val guard = AccountSwitchGuard(h.prefs, store, { events += "stop" }, { events += "start" })

    private fun owner() = h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER]

    private suspend fun accountAHasData() {
        h.preferences[AccountSwitchGuard.KEY_LEDGER_OWNER] = "a@example.com"
        h.repo.insertTransaction(h.expense("txn_a"))
        h.repo.insertTransaction(h.expense("txn_b", merchant = "Bus"))
    }

    @Test
    fun theSameEmailKeepsEverythingEvenInADifferentCase() = runBlocking {
        accountAHasData()
        val e = guard.evaluate("A@Example.com")
        assertEquals(SwitchDecision.PROCEED, e.decision)
        assertEquals(0, store.wipes)
        assertEquals(2, h.tDao.rows.size)
    }

    @Test
    fun aDifferentEmailAsksFirstWithTheRealPendingCountAndSyncsNothing() = runBlocking {
        accountAHasData()
        h.syncRequests = 0
        val e = guard.evaluate("b@example.com")
        assertEquals(SwitchDecision.CONFIRM_SWITCH, e.decision)
        assertEquals("the count comes from the real queue", 2, e.pendingCount)
        assertEquals(0, store.wipes)
        assertEquals(2, h.tDao.rows.size)
        assertEquals("no sync is requested before confirmation", 0, h.syncRequests)
        assertTrue("no request reaches Google", h.server.calls.isEmpty())
        assertEquals("a@example.com", owner())
    }

    @Test
    fun cancellingChangesNothing() = runBlocking {
        accountAHasData()
        guard.evaluate("b@example.com")
        // the user taps Cancel: switchTo is never called
        assertEquals(0, store.wipes)
        assertEquals(2, h.tDao.rows.size)
        assertEquals(2, h.qDao.queue.size)
        assertEquals("a@example.com", owner())
        assertTrue(events.isEmpty())
    }

    @Test
    fun confirmingStopsWorkWipesThenRecordsTheNewOwnerThenRestarts() = runBlocking {
        accountAHasData()
        guard.switchTo("b@example.com")
        assertEquals(listOf("stop", "wipe", "start"), events)
        assertEquals("b@example.com", owner())
        assertTrue(h.tDao.rows.isEmpty())
        assertTrue(h.qDao.queue.isEmpty())
    }

    @Test
    fun theQueueOfAccountANeverReachesGoogleWithAccountBsToken() = runBlocking {
        accountAHasData()
        guard.switchTo("b@example.com")
        h.repo.syncPendingTransactions()
        assertTrue("nothing of account A is pushed", h.server.calls.none { it.method != "GET" })
        assertEquals(0, h.qDao.queue.size)
    }

    @Test
    fun aFirstSignInWithNoDataNeedsNoQuestionButAnOwnerlessInstallWithDataDoes() = runBlocking {
        assertEquals(SwitchDecision.PROCEED, guard.evaluate("a@example.com").decision)
        h.repo.insertTransaction(h.expense("txn_a"))
        assertEquals(SwitchDecision.CONFIRM_SWITCH, guard.evaluate("a@example.com").decision)
    }

    @Test
    fun noOwnerButTheSameEmailAsBeforeSignOutDoesNotAskToWipe() = runBlocking {
        h.repo.insertTransaction(h.expense("txn_a"))
        h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL] = "Me@Example.com"
        assertEquals(SwitchDecision.PROCEED, guard.evaluate("me@example.com").decision)
        guard.recordOwner("me@example.com")
        assertEquals("me@example.com", owner())
        assertEquals(0, store.wipes)
    }

    @Test
    fun noOwnerAndADifferentEarlierEmailStillAsks() = runBlocking {
        h.repo.insertTransaction(h.expense("txn_a"))
        h.preferences[AccountSwitchGuard.KEY_LAST_EMAIL] = "me@example.com"
        assertEquals(SwitchDecision.CONFIRM_SWITCH, guard.evaluate("other@example.com").decision)
    }

    @Test
    fun noOwnerAndNoEarlierEmailAnywhereStillAsks() = runBlocking {
        h.repo.insertTransaction(h.expense("txn_a"))
        assertEquals(SwitchDecision.CONFIRM_SWITCH, guard.evaluate("me@example.com").decision)
    }

    @Test
    fun theLastEmailIsRememberedAtSignInAndSignOutAndForgottenOnAWipe() {
        val coordinator = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsSyncCoordinator.kt").readText()
        assertTrue(coordinator.substringAfter("fun onGoogleSignOut").substringBefore("fun autoProvisionMasterSheet").contains("KEY_LAST_EMAIL"))
        assertFalse(AccountSwitchGuard.KEY_LAST_EMAIL in RoomAccountDataStore.KEPT_PREFERENCE_KEYS)
    }

    @Test
    fun recordingTheOwnerNeverOverwritesAnotherAccount() = runBlocking {
        guard.recordOwner("a@example.com")
        assertEquals("a@example.com", owner())
        guard.recordOwner("b@example.com")
        assertEquals("a@example.com", owner())
        guard.recordOwner("A@example.com")
        assertEquals("A@example.com", owner())
    }

    @Test
    fun anInstallFromBeforeThisFeatureAdoptsItsSignedInAccount() = runBlocking {
        h.googleEmail = "old@example.com"
        guard.adoptSignedInAccount()
        assertEquals("old@example.com", owner())
    }

    @Test
    fun aFailingWipeStillRestartsTheWorkAndDoesNotRecordTheNewOwner() = runBlocking {
        accountAHasData()
        val failing = object : AccountDataStore {
            override suspend fun pendingCount() = 2
            override suspend fun hasLocalData() = true
            override suspend fun wipe() { events += "wipe"; throw IllegalStateException("disk full") }
        }
        val g = AccountSwitchGuard(h.prefs, failing, { events += "stop" }, { events += "start" })
        val failure = runCatching { g.switchTo("b@example.com") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("stop", "wipe", "start"), events)
        assertEquals("the owner is recorded only after a successful wipe", "a@example.com", owner())
    }

    @Test
    fun theWipeSeedsDefaultsInsideItsTransactionAndStartupChecksThemAgain() {
        val store = File("src/main/java/com/financeautomation/data/account/RoomAccountDataStore.kt").readText()
        val wipe = store.substringAfter("override suspend fun wipe()").substringBefore("companion object")
        val inside = wipe.substringAfter("db.withTransaction {").substringBefore("companion object")
        assertTrue("seeding must be part of the same transaction", inside.contains("prepopulateDefaults(db)"))
        assertTrue("nothing runs after the transaction closes", wipe.substringAfter("prepopulateDefaults(db)").trimStart().startsWith("}"))
        val app = File("src/main/java/com/financeautomation/FinanceApplication.kt").readText()
        assertTrue(app.contains("FinanceDatabase.prepopulateDefaults(db)"))
    }

    @Test
    fun startAndStopCoverTheSameWorkers() {
        val container = File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        val body = container.substringAfter("val accountSwitchGuard").substringBefore("override val peopleListRebuilder")
        val stop = body.substringAfter("stopBackgroundWork = {").substringBefore("startBackgroundWork = {")
        val start = body.substringAfter("startBackgroundWork = {")
        assertTrue(stop.contains("SyncTransactionsWorker.WORK_NAME_PERIODIC") && start.contains("SyncTransactionsWorker.enqueuePeriodic"))
        assertTrue(stop.contains("MonthlyRolloverWorker.WORK_NAME") && start.contains("MonthlyRolloverWorker.schedule"))
        assertTrue(stop.contains("SubscriptionReminderWorker.WORK_NAME") && start.contains("SubscriptionReminderWorker.schedule"))
    }

    @Test
    fun aFailedSwitchIsReportedNotContinuedAsASignIn() {
        val coordinator = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsSyncCoordinator.kt").readText()
        val confirm = coordinator.substringAfter("fun confirmAccountSwitch").substringBefore("fun cancelAccountSwitch")
        assertTrue(confirm.contains("catch"))
    }

    // what the real wipe covers

    @Test
    fun theWipeCoversEveryPerAccountTableAndKeepsOnlyTheDocumentedOnes() {
        val tables = File("src/main/java/com/financeautomation/data/local/entity").listFiles { f -> f.extension == "kt" }!!
            .flatMap { Regex("tableName = \"([a-z_]+)\"").findAll(it.readText()).map { m -> m.groupValues[1] }.toList() }
            .toSet()
        val covered = (RoomAccountDataStore.WIPED_TABLES + RoomAccountDataStore.KEPT_TABLES).toSet()
        assertEquals("a table is neither wiped nor deliberately kept", tables, covered)
        assertTrue(RoomAccountDataStore.WIPED_TABLES.containsAll(listOf("transactions", "inflows", "payables", "lineage", "sync_queue", "pending_captures", "handled_notifications", "companions", "trip_tags", "subscriptions", "subscription_cycles", "budgets", "categories", "accounts")))
    }

    @Test
    fun appLevelSettingsSurviveTheWipeAndAccountOnesDoNot() {
        val kept = RoomAccountDataStore.KEPT_PREFERENCE_KEYS
        for (k in listOf(PreferencesRepository.KEY_APP_LOCK_ENABLED, PreferencesRepository.KEY_HIDE_RECENTS_PREVIEW, PreferencesRepository.KEY_SUBSCRIPTION_REMINDER_TIME)) {
            assertTrue(k, k in kept)
        }
        for (k in listOf("key_theme_mode", "key_developer_mode", "device_id")) assertTrue(k, k in kept)
        for (k in listOf("key_spreadsheet_id", "key_spreadsheet_id_2026", "dismissed_recurring", "key_google_email", "key_active_trip")) {
            assertFalse("$k belongs to the account", k in kept)
        }
    }

    @Test
    fun aPlainSignOutWipesNothing() {
        val coordinator = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsSyncCoordinator.kt").readText()
        val signOut = coordinator.substringAfter("fun onGoogleSignOut").substringBefore("fun autoProvisionMasterSheet")
        assertFalse(signOut.contains("wipe"))
        assertFalse(signOut.contains("switchTo"))
    }

    @Test
    fun bothSignInPathsAskTheGuardBeforeDoingAnythingElse() {
        val coordinator = File("src/main/java/com/financeautomation/presentation/viewmodel/SettingsSyncCoordinator.kt").readText()
        assertTrue(coordinator.substringAfter("fun onGoogleSignInSuccess").substringBefore("fun onGoogleSignOut").contains("accountSwitchGuard"))
        val onboarding = File("src/main/java/com/financeautomation/presentation/viewmodel/OnboardingViewModel.kt").readText()
        assertTrue(onboarding.substringAfter("fun startSignIn").substringBefore("fun onConsentResult").contains("accountSwitchGuard"))
    }
}
