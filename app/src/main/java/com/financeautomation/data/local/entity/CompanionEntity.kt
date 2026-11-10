package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.Companion as DomainCompanion

/**
 * Room SQLite entity representing a friend/companion for expense splitting.
 */
@Entity(
    tableName = "companions",
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
data class CompanionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "isSelected")
    val isSelected: Boolean = false,

    @ColumnInfo(name = "balance")
    val balance: Double = 0.0,

    @ColumnInfo(name = "lastSplitTimestamp")
    val lastSplitTimestamp: Long = 0L,

    @ColumnInfo(name = "isArchived", defaultValue = "0")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "updatedAt", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Int = 0
) {
    fun toDomain(): DomainCompanion {
        return DomainCompanion(
            id = id,
            name = name,
            isSelected = isSelected,
            balance = balance,
            defaultSplitRatio = 1.0,
            isArchived = isArchived
        )
    }

    companion object {
        fun fromDomain(
            companion: DomainCompanion,
            lastSplitTimestamp: Long = 0L,
            updatedAt: Long = System.currentTimeMillis(),
            dirty: Int = 1
        ): CompanionEntity {
            return CompanionEntity(
                id = companion.id,
                name = companion.name,
                isSelected = companion.isSelected,
                balance = companion.balance,
                lastSplitTimestamp = lastSplitTimestamp,
                isArchived = companion.isArchived,
                updatedAt = updatedAt,
                dirty = dirty
            )
        }
    }
}
