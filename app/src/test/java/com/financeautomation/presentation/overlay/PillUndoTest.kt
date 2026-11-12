package com.financeautomation.presentation.overlay

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class PillUndoTest {

    @Test
    fun testUndoWithinWindow_voidsTransaction() {
        val voidedTransactions = mutableListOf<String>()
        val session = PillUndoSession(
            transactionId = "txn_456",
            windowMillis = 8000L,
            onUndo = { id -> voidedTransactions.add(id) }
        )

        assertFalse(session.isExpired)
        assertFalse(session.isTriggered)

        val success = session.triggerUndo()

        assertTrue(success)
        assertTrue(session.isTriggered)
        assertEquals(listOf("txn_456"), voidedTransactions)
    }

    @Test
    fun testUndoAfterWindow_doesNothing() {
        val voidedTransactions = mutableListOf<String>()
        val session = PillUndoSession(
            transactionId = "txn_789",
            windowMillis = 8000L,
            onUndo = { id -> voidedTransactions.add(id) }
        )

        // Window expires after 8 seconds
        session.expire()

        assertTrue(session.isExpired)

        val success = session.triggerUndo()

        assertFalse("Undo triggered after expiration must return false", success)
        assertFalse(session.isTriggered)
        assertTrue("No transaction should be voided after expiration", voidedTransactions.isEmpty())
    }

    @Test
    fun testUndoCannotBeTriggeredTwice() {
        val voidCount = java.util.concurrent.atomic.AtomicInteger(0)
        val session = PillUndoSession(
            transactionId = "txn_once",
            windowMillis = 8000L,
            onUndo = { voidCount.incrementAndGet() }
        )

        assertTrue(session.triggerUndo())
        assertEquals(1, voidCount.get())

        assertFalse("Second undo attempt must return false", session.triggerUndo())
        assertEquals(1, voidCount.get())
    }

    @Test
    fun testTimerExpiresAutomaticallyAfter8Seconds() = runTest {
        val voidedTransactions = mutableListOf<String>()
        val testScope = TestScope(testScheduler)
        var expiredCallbackCalled = false

        val session = PillUndoSession(
            transactionId = "txn_timed",
            windowMillis = 8000L,
            onUndo = { id -> voidedTransactions.add(id) }
        )

        session.startTimer(testScope) {
            expiredCallbackCalled = true
        }

        // Advance 7.9 seconds - still valid
        testScheduler.advanceTimeBy(7900L)
        assertFalse(session.isExpired)
        assertFalse(expiredCallbackCalled)

        // Advance past 8.0 seconds - expires
        testScheduler.advanceTimeBy(200L)
        assertTrue(session.isExpired)
        assertTrue(expiredCallbackCalled)

        // Attempting undo now does nothing
        assertFalse(session.triggerUndo())
        assertTrue(voidedTransactions.isEmpty())
    }
}

