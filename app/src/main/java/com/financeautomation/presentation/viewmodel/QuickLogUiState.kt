package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.SplitMode
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.TripTag

/**
 * UI state for Quick Log Bottom Sheet (Phase 5.3).
 */
data class QuickLogUiState(
    val captureId: String? = null,
    val captureTimestamp: Long? = null,
    val transactionId: String? = null,
    val sourcePackage: String = "manual",
    val kind: String = com.financeautomation.data.local.entity.PendingCaptureEntity.KIND_DEBIT,
    val amountText: String = "",
    val amount: Double = 0.0,
    val payee: String = "",
    val note: String = "",
    val selectedAccount: String = CategoryConstants.ACCOUNT_OTHER,
    val selectedCategory: String = CategoryConstants.CATEGORY_FOOD_AND_DRINK,
    val splitMode: SplitMode = SplitMode.JUST_ME,
    val availableAccounts: List<Account> = emptyList(),
    val availableCategories: List<Category> = emptyList(),
    val availableCompanions: List<Companion> = emptyList(),
    val selectedCompanionIds: Set<String> = emptySet(),
    val customShareText: String = "",
    val customShare: Double? = null,
    val myShare: Double = 0.0,
    val owedToMe: Double = 0.0,
    val activeTripTag: TripTag? = null,
    val isNeedsAmount: Boolean = false,
    val isFinancialLocked: Boolean = false,
    val isPossibleDuplicate: Boolean = false,
    val duplicateOfId: String? = null,
    val subscriptionId: String? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isVoided: Boolean = false,
    val isDismissed: Boolean = false,
    val deliveredToSheet: Boolean = false,
    val resultMessage: UiText? = null,
    val errorMessage: UiText? = null
) {
    val isSaveEnabled: Boolean
        get() {
            if (isSaving || amount <= 0.0 || kind.equals(com.financeautomation.data.local.entity.PendingCaptureEntity.KIND_CREDIT, ignoreCase = true)) return false
            if (splitMode == SplitMode.CUSTOM) {
                // a typed share that cannot be read must not fall back to the full amount silently
                if (customShareText.isNotBlank() && customShare == null) return false
                val share = customShare ?: amount
                val owed = amount - share
                if (owed > 0.009 && selectedCompanionIds.isEmpty()) {
                    return false
                }
            }
            return true
        }
}

