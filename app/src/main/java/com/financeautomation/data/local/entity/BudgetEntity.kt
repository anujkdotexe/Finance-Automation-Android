package com.financeautomation.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.financeautomation.domain.model.Budget

/**
 * Room entity for a monthly spending budget of one category.
 * updatedAt, dirty and deleted drive the Config tab mirror: a delete keeps the row as a tombstone until it is pushed.
 */
@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey
    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "monthlyAmount")
    val monthlyAmount: Double,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "dirty")
    val dirty: Int = 1,

    @ColumnInfo(name = "deleted")
    val deleted: Int = 0
) {
    fun toDomain(): Budget = Budget(
        category = category,
        monthlyAmount = monthlyAmount,
        updatedAt = updatedAt,
        deleted = deleted == 1
    )

    companion object {
        fun fromDomain(budget: Budget, dirty: Int = 1): BudgetEntity = BudgetEntity(
            category = budget.category,
            monthlyAmount = budget.monthlyAmount,
            updatedAt = budget.updatedAt,
            dirty = dirty,
            deleted = if (budget.deleted) 1 else 0
        )
    }
}
