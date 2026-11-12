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
    val updatedAt: Long
) {
    companion object {
        const val KIND_DEBIT: String = "DEBIT"
        const val KIND_CREDIT: String = "CREDIT"

        const val STATE_WAITING: String = "WAITING"
        const val STATE_AWAITING_SUCCESS: String = "AWAITING_SUCCESS"
        const val STATE_SHOWN: String = "SHOWN"
        const val STATE_DONE: String = "DONE"
        const val STATE_DISMISSED: String = "DISMISSED"

        const val FLAG_NEEDS_AMOUNT: String = "NEEDS_AMOUNT"
        const val FLAG_POSSIBLE_DUPLICATE: String = "POSSIBLE_DUPLICATE"
        const val FLAG_NEW_ACCOUNT: String = "NEW_ACCOUNT"
    }
}
