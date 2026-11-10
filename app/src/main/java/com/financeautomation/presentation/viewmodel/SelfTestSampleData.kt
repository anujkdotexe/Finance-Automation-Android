package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.constants.CategoryConstants

/**
 * Encapsulates sample data for the offline self-test simulation (Spec 7.4).
 * Kept in a dedicated helper object to maintain lean ViewModel boundaries.
 */
object SelfTestSampleData {
    const val DEFAULT_SAMPLE_PAYEE = "Coffee House (Self-Test)"
    const val SAMPLE_SOURCE_PACKAGE = "selftest"
    const val SAMPLE_AMOUNT_TEXT = "250"
    const val SAMPLE_AMOUNT = 250.0
    val SAMPLE_CATEGORY = CategoryConstants.CATEGORY_FOOD_AND_DRINK

    fun createSelfTestState(
        current: QuickLogUiState,
        samplePayee: String? = null
    ): QuickLogUiState {
        return current.copy(
            captureId = null,
            sourcePackage = SAMPLE_SOURCE_PACKAGE,
            amountText = SAMPLE_AMOUNT_TEXT,
            amount = SAMPLE_AMOUNT,
            payee = samplePayee ?: DEFAULT_SAMPLE_PAYEE,
            selectedCategory = SAMPLE_CATEGORY,
            isNeedsAmount = false,
            isPossibleDuplicate = false,
            isSaved = false,
            isDismissed = false
        )
    }
}

