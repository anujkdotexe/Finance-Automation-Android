package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Room SQLite entity for tracking processed alert notifications by key and postTime.
 * Prevents repeat captures on notification reconnect replay.
 */
@Entity(
    tableName = "handled_notifications",
    primaryKeys = ["key", "postTime"]
)
data class HandledNotificationEntity(
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "postTime")
    val postTime: Long,

    @ColumnInfo(name = "contentHash")
    val contentHash: String,

    @ColumnInfo(name = "handledAt")
    val handledAt: Long
)
