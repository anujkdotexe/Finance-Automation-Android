package com.financeautomation.domain.usecase

import com.financeautomation.core.model.Result
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.DashboardRemoteGateway
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UseCase that coordinates regenerating Dashboard formulas and category breakdown tables.
 * Pure domain logic with zero HTTP, data, presentation, or Android framework dependencies.
 */
class RegenerateDashboardUseCase(
    private val dashboardRemoteGateway: DashboardRemoteGateway,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend operator fun invoke(): Result<Unit> = withContext(ioDispatcher) {
        try {
            val spreadsheetId = preferencesRepository.getSpreadsheetIdSync()
            if (spreadsheetId.isNullOrBlank()) {
                return@withContext Result.Error(
                    IllegalArgumentException("Spreadsheet ID is not configured. Please set a valid spreadsheet ID in Settings.")
                )
            }

            val categories = categoryRepository.getAllCategories(includeArchived = true)
            dashboardRemoteGateway.regenerateDashboard(spreadsheetId, categories)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
