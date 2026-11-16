package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room SQLite entity for holding background-parsed financial alert captures before
 * presenting the prompt to the user when the payment app is closed.
 */
@Entity(tableName = "pending_captures")
data class PendingCaptureEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "sourcePackage")
    val sourcePackage: String,

    @ColumnInfo(name = "amount")
    val amount: Double?,

    @ColumnInfo(name = "payee")
    val payee: String,

    @ColumnInfo(name = "inferredAccount")
    val inferredAccount: String?,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "flags", defaultValue = "''")
    val flags: String = "",

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "notificationKey")
    val notificationKey: String?,

    @ColumnInfo(name = "contentHash")
    val contentHash: String?,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "detectedAccountHint")
    val detectedAccountHint: String? = null
) {
    companion object {
        const val KIND_DEBIT: String = "DEBIT"
        const val KIND_CREDIT: String = "CREDIT"

        const val STATE_WAITING: String = "WAITING"
        const val STATE_AWAITING_SUCCESS: String = "AWAITING_SUCCESS"
        const val STATE_SHOWN: String = "SHOWN"
        const val STATE_DONE: String = "DONE"

        /** Held while the expense is being inserted; recovered at start if the process died in between. */
        const val STATE_CLAIMED: String = "CLAIMED"
        const val STATE_DISMISSED: String = "DISMISSED"
        const val STATE_NEEDS_REVIEW: String = "NEEDS_REVIEW"

        const val FLAG_NEEDS_AMOUNT: String = "NEEDS_AMOUNT"
        const val FLAG_POSSIBLE_DUPLICATE: String = "POSSIBLE_DUPLICATE"
        const val FLAG_NEW_ACCOUNT: String = "NEW_ACCOUNT"

        /** Flag value is `SUBSCRIPTION:<subscriptionId>`: the capture pays an open subscription cycle. */
        const val FLAG_SUBSCRIPTION_PREFIX: String = "SUBSCRIPTION:"

        /** Flag value is `DUPLICATE_OF:<transactionId>`: transaction that already answered a subscription cycle. */
        const val FLAG_DUPLICATE_OF_PREFIX: String = "DUPLICATE_OF:"

        /** Returns the value of the first flag starting with [prefix], or null. */
        fun flagValue(flags: String, prefix: String): String? =
            flags.split(",").map { it.trim() }
                .firstOrNull { it.startsWith(prefix) }
                ?.removePrefix(prefix)
                ?.takeIf { it.isNotBlank() }
    }
}
