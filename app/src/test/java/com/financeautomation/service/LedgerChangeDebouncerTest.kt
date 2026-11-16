package com.financeautomation.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerChangeDebouncerTest {

    @Test
    fun debouncerCoalescesRapidEmissionsIntoSingleTrigger() = runTest {
        val debouncer = LedgerChangeDebouncer(debounceMs = 1000L)
        val transactionsFlow = MutableSharedFlow<String>()
        val inflowsFlow = MutableSharedFlow<String>()

        var callbackCount = 0
        val job = debouncer.bind(
            scope = this,
            transactionsFlow = transactionsFlow,
            inflowsFlow = inflowsFlow
        ) {
            callbackCount++
        }

        // Emit at t = 0
        transactionsFlow.emit("tx_1")
        runCurrent()
        advanceTimeBy(300L)
        assertEquals("Should not fire before debounce window", 0, callbackCount)

        // Rapid second emission at t = 300
        inflowsFlow.emit("inflow_1")
        runCurrent()
        advanceTimeBy(500L) // t = 800 (500ms since last emit)
        assertEquals("Should not fire yet, timer was reset", 0, callbackCount)

        // Rapid third emission at t = 800
        transactionsFlow.emit("tx_2")
        runCurrent()
        advanceTimeBy(999L) // t = 1799 (999ms since last emit)
        assertEquals("Should not fire at 999ms", 0, callbackCount)

        // Advance 1ms to reach 1000ms after last emit
        advanceTimeBy(1L) // t = 1800 (1000ms since last emit)
        runCurrent()
        assertEquals("Debounced trigger must fire exactly once after quiet period", 1, callbackCount)

        // Further time passing with no new emissions does not fire again
        advanceTimeBy(3000L)
        runCurrent()
        assertEquals("No extra fires without new emissions", 1, callbackCount)

        // Later emission fires again after its own 1000ms
        transactionsFlow.emit("tx_3")
        runCurrent()
        advanceTimeBy(1000L)
        runCurrent()
        assertEquals("Second batch fires after its own window", 2, callbackCount)

        job.cancel()
    }
}
