package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PipelineCode
import com.financeautomation.core.utils.PipelineStage
import com.financeautomation.core.utils.PipelineTrail
import com.financeautomation.core.utils.formatAmountInput
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * Helper extracting transaction loading and updating logic from QuickLogViewModel to preserve file size limits.
 */
object QuickLogTransactionHelper {

    suspend fun matchOrInsertCompanions(
        companionsList: List<String>,
        currentCompanions: List<Companion>,
        peopleRepository: PeopleRepository
    ): Set<String> {
        val matchedIds = mutableSetOf<String>()
        for (name in companionsList) {
            val found = currentCompanions.find { it.name.equals(name, ignoreCase = true) }
            if (found != null) {
                matchedIds.add(found.id)
            } else {
                val newId = UUID.randomUUID().toString()
                val newCompanion = Companion(id = newId, name = name)
                peopleRepository.insertCompanion(newCompanion)
                matchedIds.add(newId)
            }
        }
        return matchedIds
    }

    fun determineSplitMode(
        companions: String,
        owedToMe: Double,
        totalAmount: Double,
        myShare: Double,
        companionsList: List<String>
    ): SplitMode {
        return when {
            companions == "Personal" || owedToMe == 0.0 -> SplitMode.JUST_ME
            owedToMe > 0.0 && companionsList.isNotEmpty() -> {
                val totalHeads = companionsList.size + 1
                val expectedShare = Math.round((totalAmount / totalHeads) * 100.0) / 100.0
                if (Math.abs(myShare - expectedShare) <= 0.02) SplitMode.SPLIT_EQUALLY else SplitMode.CUSTOM
            }
            else -> SplitMode.JUST_ME
        }
    }

    suspend fun dismissCapture(
        captureId: String,
        pendingCaptureDaoProvider: suspend () -> PendingCaptureDao
    ) {
        try {
            PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.DISMISSED)
            pendingCaptureDaoProvider.invoke().updateState(
                captureId,
                PendingCaptureEntity.STATE_DISMISSED
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("QuickLogTransactionHelper", "${e.javaClass.simpleName}: onDismiss updateState DISMISSED")
        }
    }

    suspend fun loadExistingTransaction(
        txnId: String,
        currentState: QuickLogUiState,
        transactionRepository: TransactionRepository,
        peopleRepository: PeopleRepository
    ): QuickLogUiState? {
        val txn = transactionRepository.getTransactionById(txnId) ?: return null
        val companionsList = if (txn.companions.isNotBlank() && txn.companions != "Personal") {
            txn.companions.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else emptyList()

        val matchedIds = matchOrInsertCompanions(
            companionsList, currentState.availableCompanions, peopleRepository
        )
        val determinedMode = determineSplitMode(
            txn.companions, txn.owedToMe, txn.totalAmount, txn.myShare, companionsList
        )
        val isSettledOrCarried = txn.status.value.equals("Settled", ignoreCase = true) ||
            txn.status.value.equals("Carried Fwd", ignoreCase = true) ||
            (txn.owedToMe > 0.0 && txn.owedToMe < (txn.totalAmount - txn.myShare - 0.01))

        return currentState.copy(
            transactionId = txn.id,
            amount = txn.totalAmount,
            amountText = formatAmountInput(txn.totalAmount),
            payee = txn.merchant,
            selectedCategory = txn.category,
            selectedAccount = txn.account,
            splitMode = determinedMode,
            selectedCompanionIds = matchedIds,
            customShare = if (determinedMode == SplitMode.CUSTOM) txn.myShare else null,
            customShareText = if (determinedMode == SplitMode.CUSTOM) formatAmountInput(txn.myShare) else "",
            myShare = txn.myShare,
            owedToMe = txn.owedToMe,
            isFinancialLocked = isSettledOrCarried,
            isPossibleDuplicate = txn.isPossibleDuplicate,
            isSaved = false,
            isVoided = false,
            errorMessage = null
        )
    }

    suspend fun updateExisting(
        txnId: String,
        state: QuickLogUiState,
        transactionRepository: TransactionRepository
    ): Result<Unit> {
        val existing = transactionRepository.getTransactionById(txnId)
            ?: return Result.Error("Transaction not found")

        val isSettledOrCarried = existing.status.value.equals("Settled", ignoreCase = true) ||
            existing.status.value.equals("Carried Fwd", ignoreCase = true) ||
            (existing.owedToMe > 0.0 && existing.owedToMe < (existing.totalAmount - existing.myShare - 0.01))

        val updated = if (isSettledOrCarried) {
            // Item 2.2: For Settled/Carried Fwd rows or modified owed, allow only description, category, and account edits
            existing.copy(
                merchant = state.payee.ifBlank { existing.merchant },
                category = state.selectedCategory,
                account = state.selectedAccount,
                tripTag = state.activeTripTag?.name ?: existing.tripTag,
                reviewState = ReviewState.CONFIRMED,
                isPossibleDuplicate = false
            )
        } else {
            val selectedCompanions = state.availableCompanions
                .filter { state.selectedCompanionIds.contains(it.id) }
                .map { it.name }
            val companionsText = if (state.splitMode == SplitMode.JUST_ME) "Personal" else selectedCompanions.joinToString(", ")
            existing.copy(
                merchant = state.payee.ifBlank { "Expense" },
                category = state.selectedCategory,
                totalAmount = state.amount,
                myShare = state.myShare,
                owedToMe = state.owedToMe,
                companions = companionsText,
                account = state.selectedAccount,
                tripTag = state.activeTripTag?.name ?: existing.tripTag,
                reviewState = ReviewState.CONFIRMED,
                isPossibleDuplicate = false
            )
        }
        return when (val res = transactionRepository.updateTransaction(updated)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(res.message ?: "Failed to update")
            is Result.Loading -> Result.Loading
        }
    }

    suspend fun logNewExpense(
        state: QuickLogUiState,
        logExpenseUseCase: LogExpenseUseCase,
        pendingCaptureDaoProvider: suspend () -> PendingCaptureDao,
        categoryStatDaoProvider: (suspend () -> CategoryStatDao)?,
        predictCategoryUseCase: PredictCategoryUseCase
    ): Result<Unit> {
        val selectedCompanions = state.availableCompanions
            .filter { state.selectedCompanionIds.contains(it.id) }
            .map { it.name }

        val result = logExpenseUseCase(
            amount = state.amount,
            merchant = state.payee.ifBlank { "Expense" },
            category = state.selectedCategory,
            account = state.selectedAccount,
            splitMode = state.splitMode,
            companions = selectedCompanions,
            customShare = state.customShare,
            tripTag = state.activeTripTag?.name ?: "",
            sourcePackage = state.sourcePackage,
            timestamp = System.currentTimeMillis(),
            reviewState = ReviewState.CONFIRMED
        )

        if (result is Result.Success) {
            PipelineTrail.record(PipelineStage.CAPTURE, PipelineCode.DONE)
            state.captureId?.let { cid ->
                try {
                    pendingCaptureDaoProvider.invoke().updateState(cid, PendingCaptureEntity.STATE_DONE)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("QuickLogTransactionHelper", "${e.javaClass.simpleName}: updateState DONE")
                }
            }
            try {
                val statDao = categoryStatDaoProvider?.invoke()
                if (statDao != null) {
                    val key = predictCategoryUseCase.computePayeeKey(state.payee)
                    statDao.incrementStat(key, state.selectedCategory, System.currentTimeMillis())
                }
            } catch (statEx: Exception) {
                if (statEx is CancellationException) throw statEx
                Diagnostics.record("QuickLogTransactionHelper", "${statEx.javaClass.simpleName}: incrementStat")
            }
            return Result.Success(Unit)
        }
        return if (result is Result.Error) Result.Error(result.message ?: "Failed to log expense") else Result.Loading
    }
}
