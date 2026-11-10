package com.financeautomation.domain.usecase

import com.financeautomation.core.model.Result
import com.financeautomation.domain.repository.TransactionRepository

/**
 * Pure domain UseCase for triggering synchronization of pending offline transactions
 * to the master remote Google Sheets ledger.
 * Zero Android framework dependencies.
 */
class SyncTransactionsUseCase(
    private val transactionRepository: TransactionRepository
) {

    suspend operator fun invoke(): Result<Int> {
        return transactionRepository.syncPendingTransactions()
    }
}
