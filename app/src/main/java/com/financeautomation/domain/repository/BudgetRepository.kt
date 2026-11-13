package com.financeautomation.domain.repository

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Budget
import kotlinx.coroutines.flow.Flow

/**
 * Monthly budgets per category. Changes are mirrored to the Config tab.
 */
interface BudgetRepository {
    fun observeBudgets(): Flow<List<Budget>>

    /** Creates or replaces the budget of [category]. The amount must be above zero and finite. */
    suspend fun setBudget(category: String, monthlyAmount: Double): Result<Unit>

    /** Removes the budget of [category]; removing a missing budget succeeds. */
    suspend fun removeBudget(category: String): Result<Unit>
}
