package com.financeautomation.domain.usecase

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.OpenDebtItem
import com.financeautomation.domain.repository.LedgerSnapshotGateway
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.utils.AccountingUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Loads unresolved debts owed by a specific person from the master ledger snapshot (Phase 5.6 point 2).
 * Operates off the main thread with a short network timeout to ensure the UI is never blocked.
 * If offline or query fails, yields empty list to safely trigger auto-settlement fallback.
 */
class GetOpenDebtsForPersonUseCase(
    private val preferencesRepository: PreferencesRepository,
    private val ledgerSnapshotGateway: LedgerSnapshotGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val timeoutMillis: Long = 4000L
) {

    suspend operator fun invoke(person: String): List<OpenDebtItem> = withContext(ioDispatcher) {
        val trimmedPerson = person.trim()
        if (trimmedPerson.isBlank()) return@withContext emptyList()

        try {
            val spreadsheetId = preferencesRepository.getSpreadsheetIdSync() ?: return@withContext emptyList()

            val snapshotResult = withTimeoutOrNull(timeoutMillis) {
                ledgerSnapshotGateway.readLedgerSnapshot(spreadsheetId)
            } ?: return@withContext emptyList()

            if (snapshotResult !is Result.Success) return@withContext emptyList()

            val domainSnapshot = snapshotResult.data

            domainSnapshot.debits
                .filter { debit ->
                    debit.status.equals("Pending", ignoreCase = true) &&
                        debit.owed > 0.0 &&
                        AccountingUtils.tokens(debit.companions).any { it.equals(trimmedPerson, ignoreCase = true) }
                }
                .map { debit ->
                    val debtUuid = debit.uuid?.takeIf { it.isNotBlank() } ?: "${debit.tab}!${debit.row}"
                    val companionCount = AccountingUtils.tokens(debit.companions).size
                    val amountOwed = if (AccountingUtils.isEqualSplit(debit) && companionCount > 0) {
                        AccountingUtils.round2(debit.owed / companionCount)
                    } else {
                        AccountingUtils.round2(debit.owed)
                    }
                    OpenDebtItem(
                        uuid = debtUuid,
                        description = debit.item,
                        amountOwed = amountOwed,
                        tabName = debit.tab,
                        person = trimmedPerson
                    )
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("GetOpenDebtsForPersonUseCase", "${e.javaClass.simpleName}: load failed")
            emptyList()
        }
    }
}
