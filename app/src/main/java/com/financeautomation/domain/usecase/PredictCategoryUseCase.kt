package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants

private val NON_ALPHANUMERIC_OR_SPACE_REGEX = Regex("[^a-z0-9 ]")
private val MULTI_SPACE_REGEX = Regex("\\s+")

/**
 * Pure domain UseCase for computing sanitized payee keys and predicting category order (Phase 5.4).
 * Pure Kotlin with zero Android framework dependencies.
 */
class PredictCategoryUseCase {

    /**
     * Computes payeeKey per Phase 5.4.2:
     * Lower-case, remove everything except letters, digits and spaces, collapse spaces, truncate to 40 chars.
     */
    fun computePayeeKey(payee: String): String {
        val lower = payee.trim().lowercase()
        val stripped = NON_ALPHANUMERIC_OR_SPACE_REGEX.replace(lower, "")
        val collapsed = MULTI_SPACE_REGEX.replace(stripped, " ").trim()
        return collapsed.take(40)
    }

    /**
     * Ranks categories per Phase 5.4.3:
     * 1. Categories for the payee ordered by count desc then lastUsed desc.
     * 2. Global category totals for the rest.
     * 3. Configured categories not yet present.
     * 4. Fallback to CategoryConstants.CATEGORY_OTHER.
     */
    operator fun invoke(
        payee: String,
        payeeCategories: List<String>,
        globalCategories: List<String>,
        configuredCategories: List<String>
    ): List<String> {
        val result = LinkedHashSet<String>()

        // 1. Payee-specific categories
        for (cat in payeeCategories) {
            if (cat.isNotBlank()) {
                result.add(cat)
            }
        }

        // 2. Global category totals
        for (cat in globalCategories) {
            if (cat.isNotBlank()) {
                result.add(cat)
            }
        }

        // 3. Configured categories
        for (cat in configuredCategories) {
            if (cat.isNotBlank()) {
                result.add(cat)
            }
        }

        // 4. Fallback CATEGORY_OTHER
        if (result.isEmpty()) {
            result.add(CategoryConstants.CATEGORY_OTHER)
        }

        return result.toList()
    }
}

