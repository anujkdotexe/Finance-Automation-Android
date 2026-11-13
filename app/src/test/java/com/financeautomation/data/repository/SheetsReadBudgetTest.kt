package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.testutil.ScriptedSheetsServer
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import java.time.YearMonth
import java.time.ZoneId

/** Counts the requests the scripted Sheets server sees, so a quota regression shows up as a failing number. */
class SheetsReadBudgetTest {

    private val minute = 60_000L

    private fun SyncHarness.reads() = server.calls.count { it.method == "GET" }
    private fun SyncHarness.ledgerReads() = server.calls.count { it.method == "GET" && it.url.contains("values:batchGet") }
    private fun SyncHarness.valueLookups() =
        server.calls.count { it.method == "GET" && it.url.contains("/values/") && !it.url.contains("values:batchGet") }

    private suspend fun SyncHarness.save(id: String) = repo.insertTransaction(expense(id, merchant = id))

    @Test
    fun tenSavesEachFollowedByASyncReadTheWholeLedgerOnce() = runBlocking {
        val h = SyncHarness()
        repeat(10) {
            h.save("txn_$it")
            h.now += 5_000L
            assertTrue(h.repo.syncPendingTransactions() is Result.Success)
        }
        assertEquals("full ledger reads", 1, h.ledgerReads())
    }

    @Test
    fun aRoutineReconcileReadsOnlyThisMonthAndLast() = runBlocking {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = YearMonth.now(zone)
        val names = (0..5).map { now.minusMonths(it.toLong()) }.map {
            it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } + (it.year % 100).toString().padStart(2, '0')
        }
        val h = SyncHarness(tabs = names)
        h.repo.syncPendingTransactions()
        val url = URLDecoder.decode(h.server.calls.single { it.url.contains("values:batchGet") }.url, "UTF-8")
        assertTrue(url.contains("'${names[0]}'!A4:K"))
        assertTrue(url.contains("'${names[1]}'!A4:K"))
        for (old in names.drop(2)) assertFalse("$old must not be read", url.contains("'$old'!"))
    }

    @Test
    fun aFreshExpenseCostsThreeValueReadsNotSeven() = runBlocking {
        val h = SyncHarness()
        h.save("txn_a"); h.save("txn_b"); h.save("txn_c")
        h.repo.syncPendingTransactions()
        // per expense: one uuid lookup, then the key column and the uuid column to find the next free row
        assertEquals(9, h.valueLookups())
    }

    @Test
    fun anIdleSyncInsideTheIntervalMakesNoRequest() = runBlocking {
        val h = SyncHarness()
        h.repo.syncPendingTransactions()
        val before = h.server.calls.size
        h.now += 3 * minute
        h.repo.syncPendingTransactions()
        assertEquals(before, h.server.calls.size)
    }

    @Test
    fun anIdleSyncAfterTheIntervalReadsOnlyIfDriveSaysTheSheetChanged() = runBlocking {
        val h = SyncHarness()
        h.modifiedTime = "2026-11-14T10:00:00Z"
        h.repo.syncPendingTransactions()
        assertEquals(1, h.ledgerReads())

        h.now += 11 * minute
        h.repo.syncPendingTransactions() // same modified time, nothing queued
        assertEquals("unchanged sheet is not read again", 1, h.ledgerReads())

        h.modifiedTime = "2026-11-14T10:20:00Z"
        h.now += 11 * minute
        h.repo.syncPendingTransactions()
        assertEquals("a changed sheet is read", 2, h.ledgerReads())
    }

    @Test
    fun deliveringQueuedWorkAfterTheIntervalReconcilesEvenIfDriveReportsTheOldTime() = runBlocking {
        val h = SyncHarness()
        h.modifiedTime = "2026-11-14T10:00:00Z"
        h.repo.syncPendingTransactions()
        h.now += 11 * minute
        h.save("txn_a")
        h.repo.syncPendingTransactions()
        assertEquals(2, h.ledgerReads())
    }

    @Test
    fun aUserActionBypassesTheIntervalButOnlyOncePerMinute() = runBlocking {
        val h = SyncHarness()
        h.repo.syncPendingTransactions()
        assertEquals(1, h.ledgerReads())

        h.now += 5_000L
        h.repo.syncPendingTransactions(userInitiated = true)
        assertEquals("first tap reads", 2, h.ledgerReads())

        h.now += 20_000L
        h.repo.syncPendingTransactions(userInitiated = true)
        assertEquals("second tap within a minute does not", 2, h.ledgerReads())

        h.now += 61_000L
        h.repo.syncPendingTransactions(userInitiated = true)
        assertEquals("a tap after a minute reads again", 3, h.ledgerReads())
    }

    // 429

    @Test
    fun aRateLimitAnswerPausesEveryRequestForTheRetryAfterTime() = runBlocking {
        val h = SyncHarness()
        h.server.failHeaders = mapOf("Retry-After" to "120")
        var limited = true
        h.server.fail = { call, _ -> if (limited && call.method == "GET") 429 to SyncHarness.errorBody(429, "rateLimitExceeded") else null }
        h.save("txn_a")
        assertTrue(h.repo.syncPendingTransactions() is Result.Error)
        val afterFirst = h.server.calls.size

        h.now += 60_000L
        assertTrue(h.repo.syncPendingTransactions(userInitiated = true) is Result.Error)
        assertEquals("no request while cooling down, even for a user action", afterFirst, h.server.calls.size)

        limited = false
        h.now += 61_000L // 121 seconds after the 429
        assertTrue(h.repo.syncPendingTransactions() is Result.Success)
        assertTrue(h.server.calls.size > afterFirst)
    }

    @Test
    fun aRateLimitWithoutRetryAfterUsesADefaultCooldown() = runBlocking {
        val h = SyncHarness()
        h.server.fail = { call, i -> if (i == 0) 429 to SyncHarness.errorBody(429, "rateLimitExceeded") else null }
        h.save("txn_a")
        h.repo.syncPendingTransactions()
        assertTrue(h.gate.isCoolingDown())
        h.now += 61_000L
        assertFalse(h.gate.isCoolingDown())
    }

    @Test
    fun thePeopleRebuildReadsAtMostOncePerInterval() = runBlocking {
        val h = SyncHarness()
        val people = java.lang.reflect.Proxy.newProxyInstance(
            com.financeautomation.domain.repository.PeopleRepository::class.java.classLoader,
            arrayOf(com.financeautomation.domain.repository.PeopleRepository::class.java)
        ) { _, m, _ -> if (m.returnType == List::class.java) emptyList<Any>() else null } as com.financeautomation.domain.repository.PeopleRepository
        val rebuilder = PeopleListRebuilder(
            preferencesRepository = h.prefs, googleSheetsClient = h.sheets, peopleRepository = people,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, readGate = h.gate, clock = { h.now }
        )
        rebuilder.refresh()
        rebuilder.refresh()
        assertEquals(1, h.ledgerReads())
        h.now += 11 * minute
        rebuilder.refresh()
        assertEquals(2, h.ledgerReads())
    }

    // production wiring

    @Test
    fun theAppInstallsTheSharedGateAndEveryManualSyncSaysSo() {
        val container = File("src/main/java/com/financeautomation/di/AppContainer.kt").readText()
        assertTrue(container.contains("RateLimitInterceptor(sheetsReadGate)"))
        assertTrue(container.contains("readGate = sheetsReadGate"))
        assertEquals(2, Regex("readGate = sheetsReadGate").findAll(container).count())
        for (f in listOf("DashboardViewModel", "TransactionsViewModel", "ReviewInboxViewModel", "SettingsSyncCoordinator")) {
            val src = File("src/main/java/com/financeautomation/presentation/viewmodel/$f.kt").readText()
            assertTrue("$f must mark its manual sync", src.contains("syncPendingTransactions(userInitiated = true)"))
        }
    }
}
