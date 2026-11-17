package com.financeautomation.domain.model

import java.time.Instant

/**
 * Domain entity representing a single row in the hidden Config tab.
 * Pure Kotlin data model adhering to the 9-column schema:
 * Type | Key | Value1 | Value2 | Value3 | Value4 | UpdatedAt | UpdatedBy | Deleted
 */
data class ConfigRow(
    val type: String,
    val key: String,
    val values: List<String> = emptyList(),
    val updatedAt: Instant = Instant.now(),
    val updatedBy: String = "",
    val deleted: Boolean = false
) {
    val value1: String get() = values.getOrElse(0) { "" }
    val value2: String get() = values.getOrElse(1) { "" }
    val value3: String get() = values.getOrElse(2) { "" }
    val value4: String get() = values.getOrElse(3) { "" }

    val compositeKey: String get() = compositeKeyOf(type, key)

    /**
     * Converts to row cells for RAW Sheets write.
     */
    fun toRowCells(): List<Any?> {
        return listOf(
            type,
            key,
            value1,
            value2,
            value3,
            value4,
            updatedAt.toString(),
            updatedBy,
            if (deleted) "true" else ""
        )
    }

    companion object {
        /** The identity of a Config row: type and key, trimmed and lower-cased. */
        fun compositeKeyOf(type: String, key: String): String = "${type.trim().lowercase()}|${key.trim().lowercase()}"
    }
}
