package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room SQLite entity for key-value configuration and preferences.
 */
@Entity(tableName = "preferences_table")
data class PreferenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    @ColumnInfo(name = "value")
    val value: String?,

    @ColumnInfo(name = "updatedAt", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Int = 0
)
