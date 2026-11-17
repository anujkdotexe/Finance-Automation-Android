package com.financeautomation.service

import com.financeautomation.core.utils.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import com.financeautomation.domain.usecase.MonthBurnSummary
import com.financeautomation.domain.usecase.WidgetSyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MonthBurnWidgetRefreshTest {

    @Before
    fun clearDiagnostics() {
        Diagnostics.clear()
    }

    @Test
    fun loaderThrowingIsRecordedAndDoesNotCrash() = runTest {
        var failureCalls = 0
        MonthBurnWidgetProvider.refreshSafely(
            load = { throw IllegalStateException("database locked") },
            onFailure = { failureCalls++ }
        )
        assertEquals(1, failureCalls)
        val record = Diagnostics.getRecords().single { it.tag == "MonthBurnWidgetProvider" }
        assertTrue(record.reason.contains("IllegalStateException"))
        assertTrue("the exception message must not be recorded", !record.reason.contains("database locked"))
    }

    @Test
    fun cancellationIsRethrown() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = async {
            MonthBurnWidgetProvider.refreshSafely(load = {
                started.complete(Unit)
                delay(60_000)
            })
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(Diagnostics.getRecords().none { it.tag == "MonthBurnWidgetProvider" })
    }

    @Test
    fun aCancellationExceptionThrownByTheLoaderIsRethrown() = runTest {
        var thrown: Throwable? = null
        try {
            MonthBurnWidgetProvider.refreshSafely(load = { throw CancellationException("stop") })
        } catch (e: CancellationException) {
            thrown = e
        }
        assertTrue(thrown != null)
    }

    @Test
    fun slowLoaderTimesOutAndRunsTheFailureHandler() = runTest {
        var failureCalls = 0
        MonthBurnWidgetProvider.refreshSafely(
            timeoutMs = 8_000L,
            load = { delay(60_000) },
            onFailure = { failureCalls++ }
        )
        assertEquals(1, failureCalls)
        assertEquals(8_000L, testScheduler.currentTime)
        assertTrue(Diagnostics.getRecords().any { it.tag == "MonthBurnWidgetProvider" && it.reason.contains("timed out") })
    }

    @Test
    fun aLoaderThatFinishesDoesNotRecordOrRunTheFailureHandler() = runTest {
        var failureCalls = 0
        var loaded = false
        MonthBurnWidgetProvider.refreshSafely(load = { loaded = true }, onFailure = { failureCalls++ })
        assertTrue(loaded)
        assertEquals(0, failureCalls)
        assertTrue(Diagnostics.getRecords().isEmpty())
    }

    @Test
    fun noAccountDoesNotQueryTransactions() = runTest {
        var loads = 0
        val result = MonthBurnWidgetProvider.loadIfAvailable(
            hasAccountAndWorkbook = { false },
            load = { loads++; summary() }
        )
        assertNull(result)
        assertEquals(0, loads)
    }

    @Test
    fun accountAndWorkbookLoadsTheFigures() = runTest {
        val expected = summary()
        val result = MonthBurnWidgetProvider.loadIfAvailable(hasAccountAndWorkbook = { true }, load = { expected })
        assertEquals(expected, result)
    }

    private fun summary() = MonthBurnSummary(
        year = 2026, month = 11, livingBurn = 10.0, perDayAverage = 1.0, daysElapsed = 10,
        syncState = WidgetSyncState.UpToDate
    )

    @Test
    fun aFailureHandlerThatThrowsDoesNotEscape() = runTest {
        MonthBurnWidgetProvider.refreshSafely(
            load = { throw IllegalStateException("db") },
            onFailure = { throw IllegalArgumentException("widget host") }
        )
        val reasons = Diagnostics.getRecords().map { it.reason }
        assertTrue(reasons.any { it.contains("IllegalStateException") })
        assertTrue(reasons.any { it.contains("IllegalArgumentException") && it.contains("failure handler") })
    }

    @Test
    fun aFailureHandlerThatThrowsAfterATimeoutDoesNotEscape() = runTest {
        MonthBurnWidgetProvider.refreshSafely(
            timeoutMs = 100L,
            load = { delay(60_000) },
            onFailure = { throw IllegalArgumentException("widget host") }
        )
        assertTrue(Diagnostics.getRecords().any { it.reason.contains("failure handler") })
    }
}
