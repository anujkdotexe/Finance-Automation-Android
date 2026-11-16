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
 * For a newly created workbook: provisions Dashboard and the current month tab,
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: java.time.Clock = java.time.Clock.system(ZoneId.of("Asia/Kolkata"))
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
                // A stored workbook this account cannot open (403 or 404, for example after another account signed
                // in) is not repaired; fall through to the Drive search and, failing that, a fresh workbook
                val probe = googleSheetsClient.getSheetTabsDetailed(lockedStoredId)
                if (probe is Result.Error && isInaccessible(probe.exception)) null else lockedStoredId
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

                val repairRes = ensureWorkbookTabs(candidateId, year)
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
                        val repairRes = ensureWorkbookTabs(foundId, year)
                        if (repairRes is Result.Error) {
                            return@withContext Result.Error(repairRes.exception)
                        }

                        preferencesRepository.setSpreadsheetIdForYear(year, foundId)
                        val currentYear = ZonedDateTime.now(clock).year
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
            val currentYear = ZonedDateTime.now(clock).year
            if (year == currentYear) {
                preferencesRepository.setSpreadsheetId(newSpreadsheetId)
                preferencesRepository.setSpreadsheetTitle(workbookName)
            }

            // Newly created: mirror local default config to Config tab, then regenerate Dashboard
            syncConfigUseCase?.mirrorToCloud(newSpreadsheetId)
            regenerateDashboardUseCase?.invoke()

            Result.Success(newSpreadsheetId)
        }
    }

    /**
     * Creates a brand new workbook for [year] and switches the app to it. Never searches for an existing
     * workbook and leaves every old workbook untouched. The stored id changes only after the new workbook
     * is created and provisioned; on any failure the previous id stays as it was.
     */
    open suspend fun createFreshWorkbook(year: Int): Result<String> = withContext(ioDispatcher) {
        mutex.withLock {
            val folderRes = googleDriveClient.findOrCreateFolder("Personal Finance")
            if (folderRes is Result.Error) {
                return@withContext Result.Error(folderRes.exception)
            }
            val folderId = (folderRes as Result.Success).data

            val stamp = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US))
            val workbookName = "Finance$year (new $stamp)"
            val createRes = googleDriveClient.createSpreadsheet(folderId, workbookName, year)
            if (createRes is Result.Error) {
                return@withContext Result.Error(createRes.exception)
            }
            val newId = (createRes as Result.Success).data

            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
            val provRes = googleSheetsClient.provisionMasterWorkbook(newId, accounts, categories)
            if (provRes is Result.Error) {
                return@withContext Result.Error(provRes.exception)
            }

            preferencesRepository.setSpreadsheetIdForYear(year, newId)
            val currentYear = ZonedDateTime.now(clock).year
            if (year == currentYear) {
                preferencesRepository.setSpreadsheetId(newId)
                preferencesRepository.setSpreadsheetTitle(workbookName)
            }

            syncConfigUseCase?.mirrorToCloud(newId)
            regenerateDashboardUseCase?.invoke()

            Result.Success(newId)
        }
    }

    /** True when the file is not reachable for this account: missing, or forbidden for a reason other than a rate limit. */
    private fun isInaccessible(e: Throwable): Boolean = when (e) {
        is SheetNotFoundException -> true
        is GoogleSheetsApiException -> (e.statusCode == 403 || e.statusCode == 404) && !e.isRetryable()
        else -> false
    }

    private suspend fun checkTabHasValidLayout(spreadsheetId: String, tabTitle: String): Boolean {
        val range = layoutRange(tabTitle)
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

    private suspend fun ensureWorkbookTabs(spreadsheetId: String, forYear: Int = ZonedDateTime.now(clock).year): Result<Unit> {
        val tabsResult = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
        if (tabsResult is Result.Error) {
            return Result.Error(tabsResult.exception)
        }
        val tabsMap = (tabsResult as Result.Success).data
        val hasDashboard = tabsMap.containsKey("Dashboard")

        val currentYear = ZonedDateTime.now(clock).year
        val expectedMonthTab = if (forYear == currentYear) {
            ZonedDateTime.now(clock).format(DateTimeFormatter.ofPattern("MMMyy", Locale.US))
        } else {
            val yy = (forYear % 100).toString().padStart(2, '0')
            "Jan$yy"
        }

        val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
        val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()

        if (!hasDashboard) {
            val provRes = googleSheetsClient.provisionMasterWorkbook(
                spreadsheetId = spreadsheetId,
                accounts = accounts,
                categories = categories
            )
            if (provRes is Result.Error) {
                return Result.Error(provRes.exception)
            }
        } else if (!tabsMap.containsKey(expectedMonthTab) || !checkTabHasValidLayout(spreadsheetId, expectedMonthTab)) {
            val monthRes = googleSheetsClient.provisionMonthTab(
                spreadsheetId = spreadsheetId,
                tabName = expectedMonthTab,
                accounts = accounts,
                categories = categories
            )
            if (monthRes is Result.Error) {
                return Result.Error(monthRes.exception)
            }
        }
        return Result.Success(Unit)
    }

    companion object {
        /** The header row range of a month tab. The name is quoted because names like Nov26 look like cell references. */
        fun layoutRange(tabTitle: String): String = "'${tabTitle.replace("'", "''")}'!A3:AE3"
    }
}
