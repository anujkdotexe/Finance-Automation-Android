package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Room SQLite entity storing frequency and recency stats for payee categorization (Phase 5.4).
 * Used for instant on-device category prediction with zero network dependencies.
 */
@Entity(
    tableName = "category_stats",
    primaryKeys = ["payeeKey", "category"]
)
data class CategoryStatEntity(
    @ColumnInfo(name = "payeeKey")
    val payeeKey: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "count")
    val count: Int,

    @ColumnInfo(name = "lastUsed")
    val lastUsed: Long
)

