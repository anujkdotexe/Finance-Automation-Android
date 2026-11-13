package com.financeautomation.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes concurrent token refreshes share one result. A caller that arrives while another refresh is running waits,
 * sees that the cached token is no longer the stale one it came with, and reuses the new token instead of
 * refreshing again.
 */
class TokenRefreshGate {
    private val mutex = Mutex()

    suspend fun refresh(
        staleToken: String?,
        cachedToken: () -> String?,
        doRefresh: suspend () -> String?
    ): String? = mutex.withLock {
        val current = cachedToken()
        if (!current.isNullOrBlank() && current != staleToken) current else doRefresh()
    }
}

/** Allows an action at most once per [minIntervalMillis]. Thread safe. */
class MinIntervalLimiter(
    private val minIntervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var last: Long = Long.MIN_VALUE

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock()
        if (last != Long.MIN_VALUE && now - last < minIntervalMillis) return false
        last = now
        return true
    }
}
