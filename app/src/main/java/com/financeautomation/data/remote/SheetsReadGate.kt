package com.financeautomation.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the app inside the Google Sheets read quota.
 *
 * - After a rate-limit answer (HTTP 429, or 403 with a rate-limit reason) nothing reads the sheet until the cooldown
 *   given by the Retry-After header (or a default) is over.
 * - Full ledger reads (reconcile, the people list) run at most once per interval. A user action (Sync now, pull to
 *   refresh) may bypass the interval, but at most once a minute.
 *
 * The clock is injected so tests can move time. Held as one instance for the whole app.
 */
class SheetsReadGate(private val clock: () -> Long = System::currentTimeMillis) {

    @Volatile
    private var cooldownUntil = 0L

    @Volatile
    private var lastForcedAt = Long.MIN_VALUE / 2
    private val lastRunAt = ConcurrentHashMap<String, Long>()

    /** Starts a cooldown. [retryAfterSeconds] is the Retry-After header when present. */
    fun noteRateLimited(retryAfterSeconds: Long?) {
        val waitMs = if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            minOf(retryAfterSeconds * 1000L, MAX_COOLDOWN_MS)
        } else {
            DEFAULT_COOLDOWN_MS
        }
        val until = clock() + waitMs
        if (until > cooldownUntil) cooldownUntil = until
    }

    fun isCoolingDown(): Boolean = clock() < cooldownUntil

    fun cooldownRemainingMillis(): Long = maxOf(0L, cooldownUntil - clock())

    private fun isDue(key: String, minIntervalMs: Long): Boolean {
        val last = lastRunAt[key]
        return last == null || clock() - last >= minIntervalMs
    }

    private fun canForce(): Boolean = clock() - lastForcedAt >= FORCE_INTERVAL_MS

    /** Like [tryAcquire] but only looks: nothing is recorded. Lets a caller skip cheap checks when it would not run. */
    fun canRun(key: String, minIntervalMs: Long, force: Boolean = false): Boolean =
        !isCoolingDown() && (isDue(key, minIntervalMs) || (force && canForce()))

    /**
     * True when the full read named [key] may run now, and records that it did. Never true while cooling down.
     * [force] (a user action) ignores [minIntervalMs] but is itself limited to once per [FORCE_INTERVAL_MS].
     */
    fun tryAcquire(key: String, minIntervalMs: Long, force: Boolean = false): Boolean {
        if (isCoolingDown()) return false
        if (isDue(key, minIntervalMs)) {
            lastRunAt[key] = clock()
            return true
        }
        if (force && canForce()) {
            lastForcedAt = clock()
            lastRunAt[key] = clock()
            return true
        }
        return false
    }

    companion object {
        const val RECONCILE_INTERVAL_MS: Long = 10 * 60 * 1000L
        const val FORCE_INTERVAL_MS: Long = 60 * 1000L
        const val DEFAULT_COOLDOWN_MS: Long = 60 * 1000L
        const val MAX_COOLDOWN_MS: Long = 15 * 60 * 1000L
    }
}

/** Feeds rate-limit answers into the [SheetsReadGate] so every client of the shared HTTP client honours the cooldown. */
class RateLimitInterceptor(private val gate: SheetsReadGate) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val limited = response.code == 429 ||
            (response.code == 403 && response.peekBody(2048).string().contains("ratelimit", ignoreCase = true))
        if (limited) {
            gate.noteRateLimited(response.header("Retry-After")?.trim()?.toLongOrNull())
        }
        return response
    }
}
