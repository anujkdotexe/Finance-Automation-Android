package com.financeautomation.data.repository

import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.repository.CategoryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementation of [CategoryRepository] backed by Room SQLite [CategoryDao].
 */
class CategoryRepositoryImpl(
    private val categoryDao: CategoryDao,
    private val configSyncCoordinator: ConfigSyncCoordinator? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : CategoryRepository {

    override fun getCategories(includeArchived: Boolean): Flow<List<Category>> {
        val flow = if (includeArchived) {
            categoryDao.getCategoriesFlow()
        } else {
            categoryDao.getActiveCategoriesFlow()
        }
        return flow.map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getAllCategories(includeArchived: Boolean): List<Category> = withContext(ioDispatcher) {
        val entities = if (includeArchived) {
            categoryDao.getAllCategories()
        } else {
            categoryDao.getActiveCategories()
        }
        entities.map { it.toDomain() }
    }

    override suspend fun getActiveCategories(): List<Category> = withContext(ioDispatcher) {
        categoryDao.getActiveCategories().map { it.toDomain() }
    }

    override suspend fun getCategoryById(id: String): Category? = withContext(ioDispatcher) {
        categoryDao.getById(id)?.toDomain()
    }

    override suspend fun getCategoryByName(name: String): Category? = withContext(ioDispatcher) {
        categoryDao.getByName(name)?.toDomain()
    }

    override suspend fun insertCategory(category: Category): Unit = withContext(ioDispatcher) {
        categoryDao.insert(CategoryEntity.fromDomain(category))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun updateCategory(category: Category): Unit = withContext(ioDispatcher) {
        categoryDao.update(CategoryEntity.fromDomain(category))
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean): Unit = withContext(ioDispatcher) {
        categoryDao.setExcludedFromLivingBurn(id, excluded)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun archiveCategory(id: String): Unit = withContext(ioDispatcher) {
        categoryDao.archiveCategory(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun unarchiveCategory(id: String): Unit = withContext(ioDispatcher) {
        categoryDao.unarchiveCategory(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun deleteCategory(id: String): Unit = withContext(ioDispatcher) {
        categoryDao.deleteById(id)
        configSyncCoordinator?.schedulePush()
    }

    override suspend fun reorderCategories(categoryIds: List<String>): Unit = withContext(ioDispatcher) {
        categoryIds.forEachIndexed { index, id ->
            categoryDao.updateDisplayOrder(id, index)
        }
        configSyncCoordinator?.schedulePush()
    }
}
