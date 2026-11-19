package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionFrequency
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.usecase.SyncConfigUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

open class SubscriptionsSheetSyncCoordinator(
    private val googleSheetsClient: GoogleSheetsClient,
    private val subscriptionRepository: SubscriptionRepository,
    private val preferencesRepository: PreferencesRepository,
    private val accountRepository: AccountRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Synchronizes the visible Subscriptions tab with local Room SQLite.
     * Tab edits update Room, and Room rewrites Config JSON mirror.
     * On same-field conflict between sheet and Room, sheet wins (recorded with code CONFLICT_SHEET_WON).
     */
    open suspend fun syncSubscriptionsTab(spreadsheetId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val ensureRes = googleSheetsClient.ensureSubscriptionsTabExists(spreadsheetId, accounts)
            if (ensureRes is Result.Error) return@withContext Result.Error(ensureRes.exception)

            val readRes = googleSheetsClient.readSubscriptionsTab(spreadsheetId)
            if (readRes is Result.Error) return@withContext Result.Error(readRes.exception)

            val sheetRows = (readRes as Result.Success).data
            var roomModified = false
            val processedSubIds = mutableSetOf<String>()

            for (row in sheetRows) {
                if (row.name.isBlank() && row.id.isNullOrBlank()) continue

                val rowEpochDay = runCatching { LocalDate.parse(row.dueDateString).toEpochDay() }.getOrNull()
                    ?: LocalDate.now().toEpochDay()
                val rowFreq = SubscriptionFrequency.parse(row.frequency)

                if (row.id.isNullOrBlank()) {
                    // New row added on sheet without ID: generate UUID, upsert to Room, and write back ID to sheet
                    val newId = "sub_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    val newSub = Subscription(
                        id = newId,
                        name = row.name,
                        amount = row.amount,
                        frequency = rowFreq,
                        anchorEpochDay = rowEpochDay,
                        account = row.account,
                        category = Subscription.DEFAULT_CATEGORY,
                        remind = true,
                        keywords = row.keywords,
                        active = row.active,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        note = row.note
                    )
                    subscriptionRepository.upsertSubscription(newSub)
                    processedSubIds.add(newId)
                    roomModified = true

                    // Write back new ID to cell J{row.rowNumber}
                    googleSheetsClient.updateCell(
                        spreadsheetId = spreadsheetId,
                        tabName = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME,
                        cellCoordinate = "${SheetSchemaConstants.COL_LETTER_SUB_ID}${row.rowNumber}",
                        value = newId
                    )
                } else {
                    val existing = subscriptionRepository.getSubscription(row.id)
                    if (existing == null) {
                        // ID present on sheet but missing in Room: insert
                        val newSub = Subscription(
                            id = row.id,
                            name = row.name,
                            amount = row.amount,
                            frequency = rowFreq,
                            anchorEpochDay = rowEpochDay,
                            account = row.account,
                            category = Subscription.DEFAULT_CATEGORY,
                            remind = true,
                            keywords = row.keywords,
                            active = row.active,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis(),
                            note = row.note
                        )
                        subscriptionRepository.upsertSubscription(newSub)
                        processedSubIds.add(row.id)
                        roomModified = true
                    } else {
                        processedSubIds.add(row.id)
                        // Check for field conflicts
                        val hasConflict = existing.name != row.name ||
                            existing.amount != row.amount ||
                            existing.frequency != rowFreq ||
                            existing.anchorEpochDay != rowEpochDay ||
                            existing.account != row.account ||
                            existing.active != row.active ||
                            existing.note != row.note ||
                            existing.keywords != row.keywords

                        if (hasConflict) {
                            // Sheet wins!
                            Diagnostics.record("SubscriptionsSync", "CONFLICT_SHEET_WON")
                            val updatedSub = existing.copy(
                                name = row.name,
                                amount = row.amount,
                                frequency = rowFreq,
                                anchorEpochDay = rowEpochDay,
                                account = row.account,
                                active = row.active,
                                note = row.note,
                                keywords = row.keywords,
                                updatedAt = System.currentTimeMillis()
                            )
                            subscriptionRepository.upsertSubscription(updatedSub)
                            roomModified = true
                        }
                    }
                }
            }

            // If Room was modified from sheet, rewrite Config JSON mirror
            if (roomModified) {
                syncConfigUseCase?.push(spreadsheetId)
            }

            Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Mirrors all active Room subscriptions to the Subscriptions tab.
     */
    open suspend fun mirrorToSheet(spreadsheetId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val activeSubs = subscriptionRepository.getActiveSubscriptions()
            googleSheetsClient.writeSubscriptionsTab(spreadsheetId, activeSubs, accounts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
