package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.Category

/**
 * Room SQLite entity representing an expense category.
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["displayOrder"])
    ]
)
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "isDefault")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "isExcludedFromLivingBurn")
    val isExcludedFromLivingBurn: Boolean = false,

    @ColumnInfo(name = "isArchived")
    val isArchived: Boolean = false,

    @ColumnInfo(name = "displayOrder")
    val displayOrder: Int = 0,

    @ColumnInfo(name = "iconName")
    val iconName: String = "Teal",

    @ColumnInfo(name = "updatedAt", defaultValue = "0")
    val updatedAt: Long = 0L,

    @ColumnInfo(name = "dirty", defaultValue = "0")
    val dirty: Int = 0
) {
    fun toDomain(): Category {
        return Category(
            id = id,
            name = name,
            isDefault = isDefault,
            isExcludedFromLivingBurn = isExcludedFromLivingBurn,
            isArchived = isArchived,
            displayOrder = displayOrder,
            iconName = iconName,
            updatedAt = updatedAt
        )
    }

    companion object {
        fun fromDomain(
            category: Category,
            displayOrder: Int = category.displayOrder,
            updatedAt: Long = System.currentTimeMillis(),
            dirty: Int = 1
        ): CategoryEntity {
            return CategoryEntity(
                id = category.id,
                name = category.name,
                isDefault = category.isDefault,
                isExcludedFromLivingBurn = category.isExcludedFromLivingBurn,
                isArchived = category.isArchived,
                displayOrder = displayOrder,
                iconName = category.iconName,
                updatedAt = updatedAt,
                dirty = dirty
            )
        }
    }
}
