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

    /**
     * Brings an existing workbook's Dashboard to the current formulas once. Only formulas, headers and formats are
     * written, never ledger rows. The version is recorded only after the write succeeded, so a failed attempt is
     * simply made again. True when this call upgraded the workbook.
     */
    suspend fun upgradeIfNeeded(spreadsheetId: String): Result<Boolean> = withContext(ioDispatcher) {
        try {
            val applied = preferencesRepository.getPreference(versionKey(spreadsheetId))?.toIntOrNull() ?: 0
            // Without categories the category table would be rewritten empty, so wait until they are there
            if (applied >= FORMULA_VERSION || categoryRepository.getAllCategories(includeArchived = true).isEmpty()) {
                return@withContext Result.Success(false)
            }
            when (val result = invoke(spreadsheetId)) {
                is Result.Success -> Result.Success(true)
                is Result.Error -> Result.Error(result.throwable)
                is Result.Loading -> Result.Success(false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /** Rewrites the Dashboard of [targetSpreadsheetId], or of the current workbook when none is given. */
    suspend operator fun invoke(targetSpreadsheetId: String? = null): Result<Unit> = withContext(ioDispatcher) {
        try {
            val spreadsheetId = targetSpreadsheetId ?: preferencesRepository.getSpreadsheetIdSync()
            if (spreadsheetId.isNullOrBlank()) {
                return@withContext Result.Error(
                    IllegalArgumentException("Spreadsheet ID is not configured. Please set a valid spreadsheet ID in Settings.")
                )
            }

            val categories = categoryRepository.getAllCategories(includeArchived = true)
            val result = dashboardRemoteGateway.regenerateDashboard(spreadsheetId, categories)
            if (result is Result.Success) {
                preferencesRepository.setPreference(versionKey(spreadsheetId), FORMULA_VERSION.toString(), isDirty = false)
            }
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    companion object {
        /** Raise this whenever a Dashboard formula, header or format changes, so existing workbooks get it. */
        const val FORMULA_VERSION: Int = 1

        /** Local to this device: which formula version the workbook was last given. */
        fun versionKey(spreadsheetId: String): String = "key_dashboard_formula_version_$spreadsheetId"
    }
}
