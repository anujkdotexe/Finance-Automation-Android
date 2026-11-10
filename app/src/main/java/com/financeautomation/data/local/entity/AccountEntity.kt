package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.Account

/**
 * Room SQLite entity representing a funding or payment account.
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["displayOrder"])
    ]
)
data class AccountEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "lastFourDigits")
    val lastFourDigits: String? = null,

    @ColumnInfo(name = "isDefault")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "displayOrder")
    val displayOrder: Int = 0,

    @ColumnInfo(name = "updatedAt", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Int = 0
) {
    fun toDomain(): Account {
        return Account(
            id = id,
            name = name,
            lastFourDigits = lastFourDigits,
            isDefault = isDefault,
            isArchived = isArchived,
            displayOrder = displayOrder
        )
    }

    companion object {
        fun fromDomain(account: Account, updatedAt: Long = System.currentTimeMillis(), dirty: Int = 1): AccountEntity {
            return AccountEntity(
                id = account.id,
                name = account.name,
                lastFourDigits = account.lastFourDigits,
                isDefault = account.isDefault,
                isArchived = account.isArchived,
                displayOrder = account.displayOrder,
                updatedAt = updatedAt,
                dirty = dirty
            )
        }
    }
}
