package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.usecase.PredictCategoryUseCase

/**
 * Helper to rank and reorder available categories based on payee history (Design Guide 4.2).
 */
object QuickLogCategoryRanker {

    suspend fun rankCategories(
        payee: String,
        categories: List<Category>,
        currentSelected: String,
        statDaoProvider: (suspend () -> CategoryStatDao)?,
        predictCategoryUseCase: PredictCategoryUseCase
    ): Pair<List<Category>, String>? {
        val statDao = statDaoProvider?.invoke() ?: return null
        if (categories.isEmpty()) return null

        val key = predictCategoryUseCase.computePayeeKey(payee)
        val statsForPayee = statDao.getStatsForPayee(key).map { it.category }
        val globalCategories = statDao.getGlobalCategoryRank()
        val configuredNames = categories.map { it.name }

        val rankedNames = predictCategoryUseCase(payee, statsForPayee, globalCategories, configuredNames)
        val nameToCategory = categories.associateBy { it.name.lowercase() }
        val reordered = rankedNames.mapNotNull { nameToCategory[it.lowercase()] }
        val firstChoice = rankedNames.firstOrNull() ?: CategoryConstants.CATEGORY_FOOD_AND_DRINK

        val selectedCategory = if (currentSelected == CategoryConstants.CATEGORY_FOOD_AND_DRINK ||
            !categories.any { it.name.equals(currentSelected, ignoreCase = true) }
        ) {
            firstChoice
        } else {
            currentSelected
        }

        return Pair(if (reordered.isNotEmpty()) reordered else categories, selectedCategory)
    }
}

