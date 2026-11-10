package com.financeautomation.domain.repository

import com.financeautomation.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing expense categories.
 * Pure Kotlin contract with zero Android framework dependencies.
 */
interface CategoryRepository {

    /**
     * Observes categories ordered by display order.
     */
    fun getCategories(includeArchived: Boolean = false): Flow<List<Category>>

    /**
     * Retrieves all categories as a snapshot.
     */
    suspend fun getAllCategories(includeArchived: Boolean = false): List<Category>

    /**
     * Retrieves active categories as a snapshot.
     */
    suspend fun getActiveCategories(): List<Category>

    /**
     * Retrieves a category by unique identifier.
     */
    suspend fun getCategoryById(id: String): Category?

    /**
     * Retrieves a category by name.
     */
    suspend fun getCategoryByName(name: String): Category?

    /**
     * Inserts a new category.
     */
    suspend fun insertCategory(category: Category)

    /**
     * Updates an existing category.
     */
    suspend fun updateCategory(category: Category)

    /**
     * Explicitly toggles whether a category is excluded from living burn calculations.
     */
    suspend fun setExcludedFromLivingBurn(id: String, excluded: Boolean)

    /**
     * Archives a category.
     */
    suspend fun archiveCategory(id: String)

    /**
     * Restores an archived category.
     */
    suspend fun unarchiveCategory(id: String)

    /**
     * Permanently deletes a category by identifier.
     */
    suspend fun deleteCategory(id: String)

    /**
     * Reorders categories according to the provided list of IDs.
     */
    suspend fun reorderCategories(categoryIds: List<String>)
}
