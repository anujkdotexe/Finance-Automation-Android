package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SplitMode
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.TransactionRepository
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
        reviewState: ReviewState = ReviewState.CONFIRMED
    ): Result<Transaction> {
        try {
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

            val dateSerial = DateSerialUtils.toDateSerial(timestamp)
            val timeSerial = DateSerialUtils.toTimeSerial(timestamp)
            val txnId = "txn_${UUID.randomUUID().toString().replace("-", "")}"

            val transaction = Transaction(
                id = txnId,
                timestamp = timestamp,
                dateSerial = dateSerial,
                timeSerial = timeSerial,
                account = account.ifBlank { CategoryConstants.ACCOUNT_OTHER },
                merchant = merchant.trim(),
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
                isPossibleDuplicate = false
            )

            return when (val insertResult = transactionRepository.insertTransaction(transaction)) {
                is Result.Success -> Result.Success(transaction)
                is Result.Error -> Result.Error(insertResult.throwable, insertResult.message)
                is Result.Loading -> Result.Loading
            }
        } catch (e: Exception) {
            return Result.Error(e, e.message)
        }
    }
}
