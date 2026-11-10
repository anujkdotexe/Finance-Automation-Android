package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room SQLite entity representing an outbound operation queued for cloud synchronization.
 * Guarantees offline resilience and zero data loss across network disconnects.
 */
@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["transactionId"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "transactionId")
    val transactionId: String,

    @ColumnInfo(name = "operationType")
    val operationType: String,

    @ColumnInfo(name = "payloadJson")
    val payloadJson: String,

    @ColumnInfo(name = "retryCount")
    val retryCount: Int = 0,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING,

    @ColumnInfo(name = "planJson")
    val planJson: String? = null
) {
    companion object {
        const val OP_INSERT: String = "INSERT"
        const val OP_UPDATE: String = "UPDATE"
        const val OP_VOID: String = "VOID"
        const val OP_CONFIG_PUSH: String = "CONFIG_PUSH"
        const val OP_LOG_CREDIT: String = "LOG_CREDIT"
        const val OP_LOG_PAYABLE: String = "LOG_PAYABLE"
        const val OP_LOG_REPAYMENT: String = "LOG_REPAYMENT"
        const val OP_CARRY_FORWARD: String = "CARRY_FORWARD"
        const val OP_YEAR_CLOSE: String = "YEAR_CLOSE"

        const val STATUS_PENDING: String = "PENDING"
        const val STATUS_IN_PROGRESS: String = "IN_PROGRESS"
        const val STATUS_FAILED: String = "FAILED"
        const val STATUS_COMPLETED: String = "COMPLETED"
    }
}
