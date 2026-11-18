package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.TimeGuardConstants
import com.financeautomation.core.utils.isFuture
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.utils.AccountingUtils
import com.financeautomation.domain.utils.Tolerances
import java.util.UUID

/**
 * Pure domain UseCase for validating, splitting, and recording an expense transaction.
 * Generates permanent client UUIDs, applies split mathematics, and persists with zero loss.
 * Zero Android framework dependencies.
 */
class LogExpenseUseCase(
    private val transactionRepository: TransactionRepository,
    private val calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase()
) {

    suspend operator fun invoke(
        amount: Double,
        merchant: String,
        category: String,
        account: String = CategoryConstants.ACCOUNT_OTHER,
        splitMode: SplitMode = SplitMode.JUST_ME,
        companions: List<String> = emptyList(),
        customShare: Double? = null,
        tripTag: String = "",
        sourcePackage: String = "manual",
        timestamp: Long = System.currentTimeMillis(),
        reviewState: ReviewState = ReviewState.CONFIRMED,
        isPossibleDuplicate: Boolean = false,
        duplicateOfId: String? = null,
        afterInsert: (suspend (Transaction) -> Unit)? = null,
        /** Fixes the id, so saving the same capture twice writes one row. */
        transactionId: String? = null,
        note: String = ""
    ): Result<Transaction> {
        try {
            if (isFuture(timestamp)) return Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            require(amount > 0.0) { "Transaction amount must be strictly greater than zero" }
            require(merchant.isNotBlank()) { "Merchant / Item description cannot be blank" }

            val normalizedCategory = calculateSplitUseCase.normalizeCategory(category)

            val splitResult = when (splitMode) {
                SplitMode.JUST_ME -> calculateSplitUseCase.calculateJustMe(amount)
                SplitMode.SPLIT_EQUALLY -> calculateSplitUseCase.calculateSplitEqually(amount, companions)
                SplitMode.CUSTOM -> calculateSplitUseCase.calculateCustomSplit(
                    totalAmount = amount,
                    myShare = customShare ?: amount,
                    companions = companions
                )
            }

            if (splitResult.owedToMe > Tolerances.MONEY_LEFT && AccountingUtils.tokens(splitResult.companionsText).isEmpty()) {
                return Result.Error("At least one companion must be specified when an amount is owed to me")
            }

            val dateSerial = DateSerialUtils.toDateSerial(timestamp)
            val timeSerial = DateSerialUtils.toTimeSerial(timestamp)
            val txnId = transactionId ?: "txn_${UUID.randomUUID().toString().replace("-", "")}"

            val transaction = Transaction(
                id = txnId,
                timestamp = timestamp,
                dateSerial = dateSerial,
                timeSerial = timeSerial,
                account = account.ifBlank { CategoryConstants.ACCOUNT_OTHER },
                merchant = merchant.trim(),
                note = note.trim(),
                tripTag = tripTag.trim(),
                category = normalizedCategory,
                totalAmount = splitResult.totalAmount,
                companions = splitResult.companionsText,
                myShare = splitResult.myShare,
                owedToMe = splitResult.owedToMe,
                status = splitResult.status,
                sourcePackage = sourcePackage,
                reviewState = reviewState,
                syncState = SyncState.PENDING,
                sheetRowIndex = null,
                isPossibleDuplicate = isPossibleDuplicate,
                duplicateOfId = duplicateOfId
            )

            val insertResult = if (afterInsert != null) {
                transactionRepository.insertTransactionWith(transaction) { afterInsert(transaction) }
            } else {
                transactionRepository.insertTransaction(transaction)
            }
            return when (insertResult) {
                is Result.Success -> Result.Success(transaction)
                is Result.Error -> Result.Error(insertResult.throwable, insertResult.message)
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            return Result.Error(e, e.message)
        }
    }
}
