package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Resolves the Google Sheets workbook ID for a given fiscal year.
 * Checks local preferences, searches Google Drive by appProperties, or creates
 * and provisions Finance{year} inside "Personal Finance" folder.
 * Idempotent, thread-safe, and safe to call repeatedly.
 *
 * For an existing workbook: pulls Config tab first from the resolved workbook and only then regenerates Dashboard.
 * If restore fails with a transport error, dashboard regeneration is skipped and a recoverable error returned.
 * If Config tab is empty or missing, pushes local defaults once and then regenerates.
 * For a newly created workbook: provisions Dashboard, Template and current month tab,
 * mirrors default configuration to the Config tab, and regenerates Dashboard.
 */
open class WorkbookResolver(
    private val googleDriveClient: GoogleDriveClient,
    private val googleSheetsClient: GoogleSheetsClient,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository? = null,
    private val categoryRepository: CategoryRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase? = null,
    private val regenerateDashboardUseCase: RegenerateDashboardUseCase? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutex = Mutex()

    /**
     * Checks if a workbook exists for [year] without creating a new workbook or folder.
     * Queries local preferences first, then checks Google Drive via findWorkbookForYear.
     */
    open suspend fun findExisting(year: Int): Result<String?> = withContext(ioDispatcher) {
        val storedId = preferencesRepository.getSpreadsheetIdForYearSync(year)
        if (!storedId.isNullOrBlank()) {
            return@withContext Result.Success(storedId)
        }
        when (val driveSearch = googleDriveClient.findWorkbookForYear(year)) {
            is Result.Success -> Result.Success(driveSearch.data)
            is Result.Error -> Result.Error(driveSearch.exception)
            is Result.Loading -> Result.Success(null)
        }
    }

    open suspend fun resolveForYear(year: Int, forceValidateTabs: Boolean = false): Result<String> = withContext(ioDispatcher) {
        val storedId = preferencesRepository.getSpreadsheetIdForYearSync(year)
        if (!storedId.isNullOrBlank() && !forceValidateTabs) {
            return@withContext Result.Success(storedId)
        }

        mutex.withLock {
            val lockedStoredId = preferencesRepository.getSpreadsheetIdForYearSync(year)
            if (!lockedStoredId.isNullOrBlank() && !forceValidateTabs) {
                return@withContext Result.Success(lockedStoredId)
            }

            val candidateId = if (forceValidateTabs && !lockedStoredId.isNullOrBlank()) {
                lockedStoredId
            } else {
                null
            }

            if (candidateId != null) {
                // Check if workbook contains existing data rows before repair
                val metadataRes = googleSheetsClient.getSheetTabsDetailed(candidateId)
                if (metadataRes is Result.Success) {
                    val tabTitles = metadataRes.data.keys.toList()
                    val hasDataRes = googleSheetsClient.hasDataRows(candidateId, tabTitles)
                    if (hasDataRes is Result.Success && hasDataRes.data) {
                        val currentDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                        val backupName = "Finance$year (backup before repair $currentDate)"
                        val copyRes = googleDriveClient.copyFile(candidateId, backupName)
                        if (copyRes is Result.Error) {
                            return@withContext Result.Error(
                                Exception("Pre-repair backup copy failed: ${copyRes.exception.message}", copyRes.exception)
                            )
                        }
                    }
                }

                val repairRes = ensureWorkbookTabs(candidateId)
                if (repairRes is Result.Error) {
                    return@withContext Result.Error(repairRes.exception)
                }

                // Never unconditionally overwrite cloud config with local defaults on repair
                if (syncConfigUseCase != null) {
                    when (val restoreRes = syncConfigUseCase.restoreFromCloud(candidateId)) {
                        is Result.Success -> {
                            regenerateDashboardUseCase?.invoke()
                            return@withContext Result.Success(candidateId)
                        }
                        is Result.Error -> {
                            if (restoreRes.exception is EmptyOrMissingConfigTabException) {
                                syncConfigUseCase.mirrorToCloud(candidateId)
                                regenerateDashboardUseCase?.invoke()
                                return@withContext Result.Success(candidateId)
                            } else {
                                return@withContext Result.Error(
                                    Exception(
                                        "Workbook repair succeeded, but failed to restore configuration: ${restoreRes.exception.javaClass.simpleName}",
                                        restoreRes.exception
                                    )
                                )
                            }
                        }
                        else -> {}
                    }
                } else {
                    regenerateDashboardUseCase?.invoke()
                    return@withContext Result.Success(candidateId)
                }
            }

            when (val driveSearch = googleDriveClient.findWorkbookForYear(year)) {
                is Result.Success -> {
                    val foundId = driveSearch.data
                    if (!foundId.isNullOrBlank()) {
                        val repairRes = ensureWorkbookTabs(foundId)
                        if (repairRes is Result.Error) {
                            return@withContext Result.Error(repairRes.exception)
                        }

                        preferencesRepository.setSpreadsheetIdForYear(year, foundId)
                        val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
                        if (year == currentYear) {
                            preferencesRepository.setSpreadsheetId(foundId)
                        }

                        // Reinstall & new device recovery: pull Config tab FIRST from resolved workbook
                        if (syncConfigUseCase != null) {
                            when (val restoreRes = syncConfigUseCase.restoreFromCloud(foundId)) {
                                is Result.Success -> {
                                    regenerateDashboardUseCase?.invoke()
                                    return@withContext Result.Success(foundId)
                                }
                                is Result.Error -> {
                                    if (restoreRes.exception is EmptyOrMissingConfigTabException) {
                                        // Config tab is empty or absent: push local defaults once, then regenerate
                                        syncConfigUseCase.mirrorToCloud(foundId)
                                        regenerateDashboardUseCase?.invoke()
                                        return@withContext Result.Success(foundId)
                                    } else {
                                        // Transport or server error: keep the workbook id, skip dashboard regeneration, return recoverable error
                                        return@withContext Result.Error(
                                            Exception(
                                                "Found existing workbook Finance$year, but failed to restore configuration: ${restoreRes.exception.javaClass.simpleName}",
                                                restoreRes.exception
                                            )
                                        )
                                    }
                                }
                                is Result.Loading -> {}
                            }
                        } else {
                            regenerateDashboardUseCase?.invoke()
                        }
                        return@withContext Result.Success(foundId)
                    }
                }
                is Result.Error -> {
                    return@withContext Result.Error(driveSearch.exception)
                }
                is Result.Loading -> {}
            }

            val folderRes = googleDriveClient.findOrCreateFolder("Personal Finance")
            if (folderRes is Result.Error) {
                return@withContext Result.Error(folderRes.exception)
            }
            val folderId = (folderRes as Result.Success).data

            val workbookName = "Finance$year"
            val createSheetRes = googleDriveClient.findOrCreateSpreadsheet(
                folderId = folderId,
                spreadsheetName = workbookName,
                year = year
            )
            if (createSheetRes is Result.Error) {
                return@withContext Result.Error(createSheetRes.exception)
            }
            val newSpreadsheetId = (createSheetRes as Result.Success).data

            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()

            val provRes = googleSheetsClient.provisionMasterWorkbook(
                spreadsheetId = newSpreadsheetId,
                accounts = accounts,
                categories = categories
            )
            if (provRes is Result.Error) {
                return@withContext Result.Error(provRes.exception)
            }

            preferencesRepository.setSpreadsheetIdForYear(year, newSpreadsheetId)
            val currentYear = ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).year
            if (year == currentYear) {
                preferencesRepository.setSpreadsheetId(newSpreadsheetId)
            }

            // Newly created: mirror local default config to Config tab, then regenerate Dashboard
            syncConfigUseCase?.mirrorToCloud(newSpreadsheetId)
            regenerateDashboardUseCase?.invoke()

            Result.Success(newSpreadsheetId)
        }
    }

    private suspend fun checkTabHasValidLayout(spreadsheetId: String, tabTitle: String): Boolean {
        val range = "$tabTitle!A3:AE3"
        val res = googleSheetsClient.getRangeValues(spreadsheetId, range)
        if (res !is Result.Success) return false
        val rowValues = res.data.firstOrNull() ?: return false
        val firstHeader = rowValues.firstOrNull()?.toString()?.trim()
        val lastHeader = rowValues.lastOrNull()?.toString()?.trim()
        val expectedFirst = SheetSchemaConstants.SHEET_COLUMN_HEADERS["A"]
        val expectedLast = SheetSchemaConstants.SHEET_COLUMN_HEADERS["AE"]
        return firstHeader == expectedFirst &&
                lastHeader == expectedLast &&
                rowValues.size >= SheetSchemaConstants.TOTAL_COLUMN_COUNT
    }

    private suspend fun ensureWorkbookTabs(spreadsheetId: String): Result<Unit> {
        val tabsResult = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
        if (tabsResult is Result.Error) {
            return Result.Error(tabsResult.exception)
        }
        val tabsMap = (tabsResult as Result.Success).data
        val hasMasterTabs = tabsMap.containsKey("Dashboard") && tabsMap.containsKey("Template")

        val currentMonthTab = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
            .format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))

        val hasTemplateLayout = if (hasMasterTabs) {
            checkTabHasValidLayout(spreadsheetId, "Template")
        } else {
            false
        }

        val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
        val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()

        if (!hasMasterTabs || !hasTemplateLayout) {
            val provRes = googleSheetsClient.provisionMasterWorkbook(
                spreadsheetId = spreadsheetId,
                accounts = accounts,
                categories = categories
            )
            if (provRes is Result.Error) {
                return Result.Error(provRes.exception)
            }
        } else if (!tabsMap.containsKey(currentMonthTab) || !checkTabHasValidLayout(spreadsheetId, currentMonthTab)) {
            val monthRes = googleSheetsClient.provisionMonthTab(
                spreadsheetId = spreadsheetId,
                tabName = currentMonthTab,
                accounts = accounts,
                categories = categories
            )
            if (monthRes is Result.Error) {
                return Result.Error(monthRes.exception)
            }
        }
        return Result.Success(Unit)
    }
}
