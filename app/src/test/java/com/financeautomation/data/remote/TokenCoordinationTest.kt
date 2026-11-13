package com.financeautomation.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** R-1: concurrent token refreshes share one result, and the reconnect notification is rate limited. */
class TokenCoordinationTest {

    @Test
    fun concurrentRefreshesPerformOneRefreshAndShareTheNewToken() = runBlocking {
        val gate = TokenRefreshGate()
        val cached = java.util.concurrent.atomic.AtomicReference<String?>("old")
        val refreshes = AtomicInteger(0)

        val results = (1..8).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                gate.refresh(staleToken = "old", cachedToken = { cached.get() }) {
                    refreshes.incrementAndGet()
                    delay(50)
                    cached.set("new")
                    "new"
                }
            }
        }.awaitAll()

        assertEquals(1, refreshes.get())
        assertTrue(results.all { it == "new" })
    }

    @Test
    fun aLaterRefreshAfterTheTokenChangedAgainRunsAgain() = runBlocking {
        val gate = TokenRefreshGate()
        var cached: String? = "t1"
        var calls = 0
        val first = gate.refresh("t1", { cached }) { calls++; cached = "t2"; "t2" }
        val second = gate.refresh("t2", { cached }) { calls++; cached = "t3"; "t3" }
        assertEquals("t2", first)
        assertEquals("t3", second)
        assertEquals(2, calls)
    }

    @Test
    fun aFailedRefreshReturnsNullAndTheNextCallTriesAgain() = runBlocking {
        val gate = TokenRefreshGate()
        var calls = 0
        assertEquals(null, gate.refresh("old", { "old" }) { calls++; null })
        assertEquals("fresh", gate.refresh("old", { "old" }) { calls++; "fresh" })
        assertEquals(2, calls)
    }

    @Test
    fun reconnectNotificationIsLimitedToOncePerInterval() {
        var now = 1_000_000L
        val limiter = MinIntervalLimiter(minIntervalMillis = 30 * 60_000L, clock = { now })
        assertTrue(limiter.tryAcquire())
        assertFalse(limiter.tryAcquire())
        now += 29 * 60_000L
        assertFalse(limiter.tryAcquire())
        now += 2 * 60_000L
        assertTrue(limiter.tryAcquire())
    }
}
