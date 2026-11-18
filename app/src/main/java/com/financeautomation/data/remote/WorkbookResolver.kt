package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.CancellationException
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
    private val clock: java.time.Clock = java.time.Clock.system(ZoneId.of("Asia/Kolkata")),
    val onWorkbookReplaced: suspend (year: Int, oldId: String, newId: String) -> Unit = { _, _, _ -> }
) {
    private val mutex = Mutex()

    /**
     * Checks if a workbook exists for [year] without creating a new workbook or folder.
     * Queries local preferences first, then checks Google Drive via isFileUsable (line 56)
     * and findWorkbookForYear.
     */
    open suspend fun findExisting(year: Int): Result<String?> = withContext(ioDispatcher) {
        val storedId = preferencesRepository.getSpreadsheetIdForYearSync(year)
        if (!storedId.isNullOrBlank()) {
            when (val usableRes = googleDriveClient.isFileUsable(storedId)) {
                is Result.Success -> {
                    if (usableRes.data) {
                        return@withContext Result.Success(storedId)
                    }
                    // Stored id is unusable (trashed, deleted, or forbidden); fall through to Drive search below
                }
                is Result.Error -> {
                    return@withContext Result.Error(usableRes.exception)
                }
                is Result.Loading -> {}
            }
        }
        when (val driveSearch = googleDriveClient.findWorkbookForYear(year)) {
            is Result.Success -> Result.Success(driveSearch.data)
            is Result.Error -> Result.Error(driveSearch.exception)
            is Result.Loading -> Result.Success(null)
        }
    }

    open suspend fun repairWorkbook(
        year: Int,
        archiveOlderBackups: Boolean = false
    ): Result<WorkbookRepairResult> = withContext(ioDispatcher) {
        var archiveOutcome: ArchiveBackupsOutcome? = null
        val res = resolveForYearInternal(
            year = year,
            forceValidateTabs = true,
            createBackup = true,
            archiveOlderBackups = archiveOlderBackups,
            onArchiveOutcome = { archiveOutcome = it }
        )
        when (res) {
            is Result.Success -> Result.Success(WorkbookRepairResult(res.data, archiveOutcome))
            is Result.Error -> Result.Error(res.exception)
            is Result.Loading -> Result.Loading
        }
    }

    open suspend fun resolveForYear(
        year: Int,
        forceValidateTabs: Boolean = false,
        createBackup: Boolean = false,
        archiveOlderBackups: Boolean = false
    ): Result<String> = resolveForYearInternal(
        year = year,
        forceValidateTabs = forceValidateTabs,
        createBackup = createBackup,
        archiveOlderBackups = archiveOlderBackups,
        onArchiveOutcome = null
    )

    private suspend fun resolveForYearInternal(
        year: Int,
        forceValidateTabs: Boolean = false,
        createBackup: Boolean = false,
        archiveOlderBackups: Boolean = false,
        onArchiveOutcome: ((ArchiveBackupsOutcome) -> Unit)? = null
    ): Result<String> = withContext(ioDispatcher) {
        val today = LocalDate.now(clock).toString()
        val lastVerified = preferencesRepository.getPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year))
        val pendingRequeueYear = preferencesRepository.getPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR)
        val hasPendingRequeue = pendingRequeueYear == year.toString()
        val needsVerification = forceValidateTabs || hasPendingRequeue || (lastVerified != today)

        val storedId = preferencesRepository.getSpreadsheetIdForYearSync(year)
        if (!storedId.isNullOrBlank() && !needsVerification) {
            return@withContext Result.Success(storedId)
        }

        mutex.withLock {
            val lockedStoredId = preferencesRepository.getSpreadsheetIdForYearSync(year)
            val lockedLastVerified = preferencesRepository.getPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year))
            val lockedPendingRequeueYear = preferencesRepository.getPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR)
            val lockedHasPendingRequeue = lockedPendingRequeueYear == year.toString()
            val lockedNeedsVerification = forceValidateTabs || lockedHasPendingRequeue || (lockedLastVerified != today)

            if (!lockedStoredId.isNullOrBlank() && !lockedNeedsVerification) {
                return@withContext Result.Success(lockedStoredId)
            }

            var candidateId: String? = null
            if (!lockedStoredId.isNullOrBlank()) {
                when (val usableRes = googleDriveClient.isFileUsable(lockedStoredId)) {
                    is Result.Success -> {
                        if (usableRes.data) {
                            if (!forceValidateTabs && !lockedHasPendingRequeue) {
                                preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
                                return@withContext Result.Success(lockedStoredId)
                            }
                            // Valid in Drive; now probe Sheets tabs if forceValidateTabs or has pending requeue
                            val probe = googleSheetsClient.getSheetTabsDetailed(lockedStoredId)
                            candidateId = if (probe is Result.Error && isInaccessible(probe.exception)) null else lockedStoredId
                        } else {
                            // File is trashed, 404, or non-retryable 403 in Drive: unusable!
                            candidateId = null
                        }
                    }
                    is Result.Error -> {
                        return@withContext Result.Error(usableRes.exception)
                    }
                    is Result.Loading -> {}
                }
            }

            if (candidateId != null) {
                if (createBackup) {
                    // Check if workbook contains existing data rows before repair
                    val metadataRes = googleSheetsClient.getSheetTabsDetailed(candidateId)
                    if (metadataRes is Result.Success) {
                        val tabTitles = metadataRes.data.keys.toList()
                        val hasDataRes = googleSheetsClient.hasDataRows(candidateId, tabTitles)
                        if (hasDataRes is Result.Success && hasDataRes.data) {
                            // Query existing backups before copying
                            val existingBackupsRes = googleDriveClient.findBackups(year)
                            val olderBackups = if (existingBackupsRes is Result.Success) {
                                existingBackupsRes.data
                            } else {
                                if (archiveOlderBackups && existingBackupsRes is Result.Error) {
                                    Diagnostics.record("WorkbookResolver", "findBackups_failed: ${existingBackupsRes.exception.message}")
                                    onArchiveOutcome?.invoke(ArchiveBackupsOutcome(failedCount = 0, error = existingBackupsRes.exception))
                                }
                                emptyList()
                            }

                            val currentDate = LocalDate.now(ZoneId.of("Asia/Kolkata"))
                            val backupName = "Finance$year (backup before repair $currentDate)"
                            val copyRes = googleDriveClient.copyFile(candidateId, backupName, year = year)
                            if (copyRes is Result.Error) {
                                return@withContext Result.Error(
                                    Exception("Pre-repair backup copy failed: ${copyRes.exception.message}", copyRes.exception)
                                )
                            }

                            // Move older backups to Archive only after the new copy succeeds
                            if (archiveOlderBackups && olderBackups.isNotEmpty()) {
                                val rootFolderRes = googleDriveClient.findOrCreateFolder("Personal Finance")
                                if (rootFolderRes is Result.Error) {
                                    Diagnostics.record("WorkbookResolver", "findOrCreateFolder_failed: ${rootFolderRes.exception.message}")
                                    onArchiveOutcome?.invoke(ArchiveBackupsOutcome(failedCount = olderBackups.size, error = rootFolderRes.exception))
                                } else {
                                    val rootFolderId = (rootFolderRes as Result.Success).data
                                    val archiveFolderRes = googleDriveClient.findOrCreateArchiveFolder(rootFolderId)
                                    if (archiveFolderRes is Result.Error) {
                                        Diagnostics.record("WorkbookResolver", "findOrCreateArchiveFolder_failed: ${archiveFolderRes.exception.message}")
                                        onArchiveOutcome?.invoke(ArchiveBackupsOutcome(failedCount = olderBackups.size, error = archiveFolderRes.exception))
                                    } else {
                                        val archiveFolderId = (archiveFolderRes as Result.Success).data
                                        var moved = 0
                                        var failed = 0
                                        var alreadyArchived = 0
                                        for (backup in olderBackups) {
                                            if (archiveFolderId in backup.parents) {
                                                alreadyArchived++
                                                continue
                                            }
                                            val removeParentId = backup.parents.filter { it != archiveFolderId }.joinToString(",").ifEmpty { rootFolderId }
                                            val moveRes = googleDriveClient.moveFile(backup.id, archiveFolderId, removeParentId)
                                            if (moveRes is Result.Success) {
                                                moved++
                                            } else {
                                                failed++
                                                val errMsg = (moveRes as? Result.Error)?.exception?.message.orEmpty()
                                                Diagnostics.record("WorkbookResolver", "moveFile_failed: $errMsg")
                                            }
                                        }
                                        Diagnostics.record("WorkbookResolver", "archive_backups: moved=$moved, failed=$failed, alreadyArchived=$alreadyArchived")
                                        onArchiveOutcome?.invoke(
                                            ArchiveBackupsOutcome(
                                                movedCount = moved,
                                                failedCount = failed,
                                                alreadyInArchiveCount = alreadyArchived
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val repairRes = ensureWorkbookTabs(candidateId, year)
                if (repairRes is Result.Error) {
                    return@withContext Result.Error(repairRes.exception)
                }

                if (lockedHasPendingRequeue) {
                    triggerWorkbookReplaced(year, "", candidateId, isMissingOrTrashed = true)
                }

                // Never unconditionally overwrite cloud config with local defaults on repair
                if (syncConfigUseCase != null) {
                    when (val restoreRes = syncConfigUseCase.restoreFromCloud(candidateId)) {
                        is Result.Success -> {
                            preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
                            regenerateDashboardUseCase?.invoke()
                            return@withContext Result.Success(candidateId)
                        }
                        is Result.Error -> {
                            if (restoreRes.exception is EmptyOrMissingConfigTabException) {
                                preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
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
                    preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
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
                        preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
                        val currentYear = ZonedDateTime.now(clock).year
                        if (year == currentYear) {
                            preferencesRepository.setSpreadsheetId(foundId)
                        }

                        val shouldTriggerRequeue = (!lockedStoredId.isNullOrBlank() && foundId != lockedStoredId) || lockedHasPendingRequeue
                        if (shouldTriggerRequeue) {
                            triggerWorkbookReplaced(year, lockedStoredId.orEmpty(), foundId, isMissingOrTrashed = true)
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
            preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
            val currentYear = ZonedDateTime.now(clock).year
            if (year == currentYear) {
                preferencesRepository.setSpreadsheetId(newSpreadsheetId)
                preferencesRepository.setSpreadsheetTitle(workbookName)
            }

            val shouldTriggerRequeue = (!lockedStoredId.isNullOrBlank() && newSpreadsheetId != lockedStoredId) || lockedHasPendingRequeue
            if (shouldTriggerRequeue) {
                triggerWorkbookReplaced(year, lockedStoredId.orEmpty(), newSpreadsheetId, isMissingOrTrashed = true)
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
    open suspend fun createFreshWorkbook(
        year: Int,
        copyHistory: Boolean = false
    ): Result<String> = withContext(ioDispatcher) {
        mutex.withLock {
            val oldStoredId = preferencesRepository.getSpreadsheetIdForYearSync(year)
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
            val today = LocalDate.now(clock).toString()
            preferencesRepository.setPreference(PreferencesRepository.keyLastWorkbookVerifiedDate(year), today, isDirty = false)
            val currentYear = ZonedDateTime.now(clock).year
            if (year == currentYear) {
                preferencesRepository.setSpreadsheetId(newId)
                preferencesRepository.setSpreadsheetTitle(workbookName)
            }

            if (copyHistory) {
                try {
                    onWorkbookReplaced(year, oldStoredId.orEmpty(), newId)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("WorkbookResolver", "REQUEUE_FAILED: ${e.javaClass.simpleName}")
                    preferencesRepository.setPreference(
                        PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR,
                        year.toString(),
                        isDirty = false
                    )
                }
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
        val expectedPayableUuid = SheetSchemaConstants.COL_NAME_PAYABLE_UUID
        return firstHeader == expectedFirst &&
                lastHeader == expectedPayableUuid &&
                rowValues.size >= SheetSchemaConstants.TOTAL_COLUMN_COUNT_V1
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

    private suspend fun triggerWorkbookReplaced(
        year: Int,
        oldId: String,
        newId: String,
        isMissingOrTrashed: Boolean
    ) {
        try {
            onWorkbookReplaced(year, oldId, newId)
            preferencesRepository.deletePreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR)
            if (isMissingOrTrashed) {
                preferencesRepository.setPreference(
                    PreferencesRepository.KEY_WORKBOOK_REPLACED_NOTICE_PENDING,
                    "true",
                    isDirty = false
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("WorkbookResolver", "REQUEUE_FAILED: ${e.javaClass.simpleName}")
            preferencesRepository.setPreference(
                PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR,
                year.toString(),
                isDirty = false
            )
        }
    }

    companion object {
        /** The header row range of a month tab. The name is quoted because names like Nov26 look like cell references. */
        fun layoutRange(tabTitle: String): String = "'${tabTitle.replace("'", "''")}'!A3:AF3"
    }
}

data class WorkbookRepairResult(
    val spreadsheetId: String,
    val archiveOutcome: ArchiveBackupsOutcome? = null
)

data class ArchiveBackupsOutcome(
    val movedCount: Int = 0,
    val failedCount: Int = 0,
    val alreadyInArchiveCount: Int = 0,
    val error: Throwable? = null
)
