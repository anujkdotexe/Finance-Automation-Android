package com.financeautomation.core.utils

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Lightweight diagnostics recorder for unexpected errors and lifecycle anomalies.
 * Stores a small capped in-memory buffer of redacted records.
 * Never stores or logs raw notification text, response bodies, tokens, amounts, or secrets.
 */
object Diagnostics {

    private const val MAX_CAPACITY: Int = 50

    data class Record(
        val timestamp: Long,
        val tag: String,
        val reason: String
    )

    private val records = ConcurrentLinkedDeque<Record>()

    fun record(tag: String, reason: String) {
        val entry = Record(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            reason = reason
        )
        records.addLast(entry)
        while (records.size > MAX_CAPACITY) {
            records.pollFirst()
        }
    }

    fun getRecords(): List<Record> {
        return records.toList()
    }

    fun clear() {
        records.clear()
    }
}
