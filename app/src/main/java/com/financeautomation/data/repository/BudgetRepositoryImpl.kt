package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.dao.BudgetDao
import com.financeautomation.data.local.entity.BudgetEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Budget
import com.financeautomation.domain.repository.BudgetRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class BudgetRepositoryImpl(
    private val budgetDaoProvider: suspend () -> BudgetDao,
    private val configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : BudgetRepository {

    constructor(
        budgetDao: BudgetDao,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        clock: () -> Long = { System.currentTimeMillis() }
    ) : this(
        budgetDaoProvider = { budgetDao },
        configSyncCoordinatorProvider = null,
        ioDispatcher = ioDispatcher,
        clock = clock
    )

    override fun observeBudgets(): Flow<List<Budget>> = flow {
        emitAll(budgetDaoProvider().getBudgetsFlow().map { list -> list.map { it.toDomain() } })
    }

    override suspend fun setBudget(category: String, monthlyAmount: Double): Result<Unit> = withContext(ioDispatcher) {
        val name = category.trim()
        if (name.isEmpty()) return@withContext Result.Error(IllegalArgumentException("Category is required"))
        if (!monthlyAmount.isFinite() || monthlyAmount <= 0.0) {
            return@withContext Result.Error(IllegalArgumentException("Budget must be above zero"))
        }
        try {
            val dao = budgetDaoProvider()
            // Keep the stored spelling when the category already has a budget, so names do not duplicate by case
            val existing = dao.getAllIncludingDeleted().firstOrNull { it.category.equals(name, ignoreCase = true) }
            val key = existing?.category ?: name
            dao.upsert(BudgetEntity(category = key, monthlyAmount = monthlyAmount, updatedAt = clock(), dirty = 1, deleted = 0))
            configSyncCoordinatorProvider?.invoke()?.schedulePush()
            Result.Success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Error(e)
        }
    }

    override suspend fun renameCategory(oldCategory: String, newCategory: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val from = oldCategory.trim()
            val to = newCategory.trim()
            if (from.isBlank() || to.isBlank() || from.equals(to, ignoreCase = true)) {
                return@withContext Result.Success(Unit)
            }
            val dao = budgetDaoProvider()
            dao.updateCategoryName(from, to, clock())
            configSyncCoordinatorProvider?.invoke()?.schedulePush()
            Result.Success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Error(e)
        }
    }

    override suspend fun removeBudget(category: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val dao = budgetDaoProvider()
            val existing = dao.getAll().firstOrNull { it.category.equals(category.trim(), ignoreCase = true) }
                ?: return@withContext Result.Success(Unit)
            dao.markDeleted(existing.category, clock())
            configSyncCoordinatorProvider?.invoke()?.schedulePush()
            Result.Success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.Error(e)
        }
    }
}
