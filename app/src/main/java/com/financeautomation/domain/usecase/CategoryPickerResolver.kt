package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Category

/**
 * Resolves the unified list of categories for expense and transaction logging pickers.
 * Guarantees default categories + active custom categories, deduplicated case-insensitively
 * with whitespace trimmed.
 */
object CategoryPickerResolver {

    fun resolve(configuredCategories: List<Category> = emptyList()): List<String> {
        val result = mutableListOf<String>()
        val seenKeys = mutableSetOf<String>()

        // 1. Add standard defaults first
        for (defaultCat in CategoryConstants.DEFAULT_CATEGORIES) {
            val key = defaultCat.trim().lowercase()
            if (seenKeys.add(key)) {
                result.add(defaultCat.trim())
            }
        }

        // 2. Add non-archived user-configured categories
        val activeCustom = configuredCategories
            .filterNot { it.isArchived }
            .sortedBy { it.displayOrder }

        for (customCat in activeCustom) {
            val trimmedName = customCat.name.trim()
            if (trimmedName.isEmpty()) continue
            val key = trimmedName.lowercase()
            if (seenKeys.add(key)) {
                result.add(trimmedName)
            }
        }

        return result
    }
}
