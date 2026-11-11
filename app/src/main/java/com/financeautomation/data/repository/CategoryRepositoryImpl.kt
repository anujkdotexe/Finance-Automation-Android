package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementation of [CategoryRepository] backed by Room SQLite [CategoryDao]
 * or a suspend DAO provider for main-thread safety.
 */
class CategoryRepositoryImpl(
    private val categoryDao: CategoryDao? = null,
    private val categoryDaoProvider: (suspend () -> CategoryDao)? = null,
    private val configSyncCoordinatorProvider: (() -> ConfigSyncCoordinator)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CategoryRepository {

    constructor(
        categoryDao: CategoryDao,
        configSyncCoordinator: ConfigSyncCoordinator? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        categoryDao = categoryDao,
        categoryDaoProvider = null,
        configSyncCoordinatorProvider = configSyncCoordinator?.let { { it } },
        ioDispatcher = ioDispatcher
    )

    private val configSyncCoordinator: ConfigSyncCoordinator?
        get() = configSyncCoordinatorProvider?.invoke()

    private suspend fun resolveDao(): CategoryDao =
        categoryDao ?: categoryDaoProvider?.invoke()
            ?: throw IllegalStateException("CategoryDao or provider must be supplied")

    override fun getCategories(includeArchived: Boolean): Flow<List<Category>> = flow {
        val dao = resolveDao()
        val baseFlow = if (includeArchived) {
            dao.getCategoriesFlow()
        } else {
            dao.getActiveCategoriesFlow()
        }
        emitAll(baseFlow.map { entities -> entities.map { it.toDomain() } })
    }.flowOn(ioDispatcher)

    override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = withContext(ioDispatcher) {
        val dao = resolveDao()
        val entities = if (includeArchived) {
            dao.getAllCategories()
        } else {
            dao.getActiveCategories()
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getActiveCategories(): List<Category> = withContext(ioDispatcher) {
        resolveDao().getActiveCategories().map { it.toDomain() }
    }

    override suspend fun getCategoryById(id: String): Category? = withContext(ioDispatcher) {
        resolveDao().getById(id)?.toDomain()
    }

    override suspend fun getCategoryByName(name: String): Category? = withContext(ioDispatcher) {
        resolveDao().getByName(name)?.toDomain()
    }

    override suspend fun insertCategory(category: Category): Unit = withContext(ioDispatcher) {
        resolveDao().insert(CategoryEntity.fromDomain(category))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateCategory(category: Category): Unit = withContext(ioDispatcher) {
        resolveDao().update(CategoryEntity.fromDomain(category))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean): Unit = withContext(ioDispatcher) {
        resolveDao().setExcludedFromLivingBurn(id, excluded)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveCategory(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().archiveCategory(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun unarchiveCategory(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().unarchiveCategory(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteCategory(id: String): Unit = withContext(ioDispatcher) {
        resolveDao().deleteById(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun reorderCategories(categoryIds: List<String>): Unit = withContext(ioDispatcher) {
        val dao = resolveDao()
        categoryIds.forEachIndexed { index, id ->
            dao.updateDisplayOrder(id, index)
        }
        configSyncCoordinator?.schedulePush()
    }
}
