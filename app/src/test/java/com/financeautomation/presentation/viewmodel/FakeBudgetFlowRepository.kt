package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.repository.BudgetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In memory budgets for view model tests that read budget progress. */
class FakeBudgetFlowRepository(initial: Map<String, Double> = emptyMap()) : BudgetRepository {
    private val state = MutableStateFlow(initial.map { Budget(it.key, it.value, updatedAt = 1L) })

    override fun observeBudgets(): Flow<List<Budget>> = state

    override suspend fun setBudget(category: String, monthlyAmount: Double): Result<Unit> {
        state.value = state.value.filter { it.category != category } + Budget(category, monthlyAmount, updatedAt = 1L)
        return Result.Success(Unit)
    }

    override suspend fun removeBudget(category: String): Result<Unit> {
        state.value = state.value.filter { it.category != category }
        return Result.Success(Unit)
    }
}
