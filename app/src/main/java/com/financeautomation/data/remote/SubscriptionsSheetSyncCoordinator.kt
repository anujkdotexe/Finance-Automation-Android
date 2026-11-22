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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

open class SubscriptionsSheetSyncCoordinator(
    private val googleSheetsClient: GoogleSheetsClient,
    private val subscriptionRepository: SubscriptionRepository,
    private val preferencesRepository: PreferencesRepository? = null,
    private val accountRepository: AccountRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase? = null,
    private val clock: java.time.Clock = java.time.Clock.system(com.financeautomation.core.utils.DateSerialUtils.SHEET_ZONE),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val baseHashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Only one tab sync runs at a time, so two callers cannot both create a subscription from the same blank id row. */
    private val syncMutex = Mutex()

    private suspend fun getBaseHash(id: String): String? {
        baseHashes[id]?.let { return it }
        val persisted = preferencesRepository?.getPreference("$PREFIX_SUB_BASE_HASH$id")
        if (persisted != null) {
            baseHashes[id] = persisted
        }
        return persisted
    }

    private suspend fun hasBaseHash(id: String): Boolean {
        return baseHashes.containsKey(id) || preferencesRepository?.getPreference("$PREFIX_SUB_BASE_HASH$id") != null
    }

    private suspend fun saveBaseHash(id: String, hash: String) {
        baseHashes[id] = hash
        preferencesRepository?.setPreference("$PREFIX_SUB_BASE_HASH$id", hash, isDirty = false)
    }

    private fun parseDateToEpochDay(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val serial = dateStr.toDoubleOrNull()?.toInt()
        if (serial != null && serial > 30000) {
            return runCatching { com.financeautomation.core.utils.DateSerialUtils.fromDateSerial(serial).toEpochDay() }.getOrNull()
        }
        return runCatching { LocalDate.parse(dateStr).toEpochDay() }.getOrNull()
    }

    private fun computeRowHash(
        name: String,
        amount: Double?,
        frequency: String,
        epochDay: Long,
        account: String,
        active: Boolean,
        note: String,
        keywords: String
    ): String = "$name|$amount|$frequency|$epochDay|$account|$active|$note|$keywords"

    private fun computeSubHash(sub: Subscription): String =
        computeRowHash(sub.name, sub.amount, sub.frequency.name, sub.anchorEpochDay, sub.account, sub.active, sub.note, sub.keywords)

    /**
     * Synchronizes the visible Subscriptions tab with local Room SQLite.
     * Tab edits update Room, and Room rewrites Config JSON mirror.
     * On same-field conflict between sheet and Room, sheet wins (recorded with code CONFLICT_SHEET_WON).
     */
    open suspend fun syncSubscriptionsTab(spreadsheetId: String): Result<Unit> =
        syncMutex.withLock { syncSubscriptionsTabLocked(spreadsheetId) }

    private suspend fun syncSubscriptionsTabLocked(spreadsheetId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val ensureRes = googleSheetsClient.ensureSubscriptionsTabExists(spreadsheetId, accounts)
            if (ensureRes is Result.Error) return@withContext Result.Error(ensureRes.exception)

            val readRes = googleSheetsClient.readSubscriptionsTab(spreadsheetId)
            if (readRes is Result.Error) return@withContext Result.Error(readRes.exception)

            val sheetRows = (readRes as Result.Success).data
            var roomModified = false
            val processedSubIds = mutableSetOf<String>()
            val todayEpochDay = LocalDate.now(clock).toEpochDay()
            val nowMillis = clock.millis()

            for (row in sheetRows) {
                if (row.name.isBlank() && row.id.isNullOrBlank()) continue

                val parsedEpochDay = parseDateToEpochDay(row.dueDateString)
                val rowFreq = SubscriptionFrequency.parse(row.frequency)

                if (row.id.isNullOrBlank()) {
                    val resolvedEpochDay = if (parsedEpochDay != null) {
                        parsedEpochDay
                    } else {
                        Diagnostics.record("SubscriptionsSync", "REJECTED_BAD_DATE")
                        todayEpochDay
                    }
                    // A row whose id cell is blank may already have a local subscription from an earlier sync whose id
                    // write back failed: reuse it instead of creating a duplicate.
                    val reusable = subscriptionRepository.getActiveSubscriptions().firstOrNull {
                        it.id !in processedSubIds &&
                            it.name.trim().equals(row.name.trim(), ignoreCase = true) &&
                            it.anchorEpochDay == resolvedEpochDay
                    }
                    if (reusable != null) {
                        processedSubIds.add(reusable.id)
                        val reuseRes = googleSheetsClient.updateCell(
                            spreadsheetId = spreadsheetId,
                            tabName = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME,
                            cellCoordinate = "${SheetSchemaConstants.COL_LETTER_SUB_ID}${row.rowNumber}",
                            value = reusable.id
                        )
                        if (reuseRes is Result.Error) {
                            Diagnostics.record("SubscriptionsSync", "ID_WRITEBACK_FAILED")
                        }
                        continue
                    }
                    val newId = "sub_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    val newSub = Subscription(
                        id = newId,
                        name = row.name,
                        amount = row.amount,
                        frequency = rowFreq,
                        anchorEpochDay = resolvedEpochDay,
                        account = row.account,
                        category = Subscription.DEFAULT_CATEGORY,
                        remind = true,
                        keywords = row.keywords,
                        active = row.active,
                        createdAt = nowMillis,
                        updatedAt = nowMillis,
                        note = row.note,
                        closedDateEpochDay = if (!row.active) todayEpochDay else null
                    )
                    subscriptionRepository.upsertSubscription(newSub)
                    processedSubIds.add(newId)
                    roomModified = true
                    saveBaseHash(newId, computeSubHash(newSub))

                    val writeBack = googleSheetsClient.updateCell(
                        spreadsheetId = spreadsheetId,
                        tabName = SheetSchemaConstants.SUBSCRIPTIONS_TAB_NAME,
                        cellCoordinate = "${SheetSchemaConstants.COL_LETTER_SUB_ID}${row.rowNumber}",
                        value = newId
                    )
                    if (writeBack is Result.Error) {
                        // The next sync finds the row without an id and reuses this subscription by name and due date
                        Diagnostics.record("SubscriptionsSync", "ID_WRITEBACK_FAILED")
                    }
                } else {
                    val existing = subscriptionRepository.getSubscription(row.id)
                    if (existing == null) {
                        // If ID was previously synced and tracked in baseHashes, it was deleted in Room: do not resurrect!
                        if (hasBaseHash(row.id)) {
                            Diagnostics.record("SubscriptionsSync", "DELETED_ROW_NOT_RESURRECTED")
                            continue
                        }
                        val resolvedEpochDay = if (parsedEpochDay != null) {
                            parsedEpochDay
                        } else {
                            Diagnostics.record("SubscriptionsSync", "REJECTED_BAD_DATE")
                            todayEpochDay
                        }
                        val newSub = Subscription(
                            id = row.id,
                            name = row.name,
                            amount = row.amount,
                            frequency = rowFreq,
                            anchorEpochDay = resolvedEpochDay,
                            account = row.account,
                            category = Subscription.DEFAULT_CATEGORY,
                            remind = true,
                            keywords = row.keywords,
                            active = row.active,
                            createdAt = nowMillis,
                            updatedAt = nowMillis,
                            note = row.note,
                            closedDateEpochDay = if (!row.active) todayEpochDay else null
                        )
                        subscriptionRepository.upsertSubscription(newSub)
                        processedSubIds.add(row.id)
                        roomModified = true
                        saveBaseHash(row.id, computeSubHash(newSub))
                    } else {
                        processedSubIds.add(row.id)
                        val resolvedEpochDay = if (parsedEpochDay != null) {
                            parsedEpochDay
                        } else {
                            Diagnostics.record("SubscriptionsSync", "REJECTED_BAD_DATE")
                            existing.anchorEpochDay
                        }

                        val rowHash = computeRowHash(
                            row.name, row.amount, row.frequency, resolvedEpochDay,
                            row.account, row.active, row.note, row.keywords
                        )
                        val lastBaseHash = getBaseHash(row.id)
                        val sheetChanged = lastBaseHash == null || lastBaseHash != rowHash

                        if (sheetChanged) {
                            val hasConflict = existing.name != row.name ||
                                existing.amount != row.amount ||
                                existing.frequency != rowFreq ||
                                existing.anchorEpochDay != resolvedEpochDay ||
                                existing.account != row.account ||
                                existing.active != row.active ||
                                existing.note != row.note ||
                                existing.keywords != row.keywords

                            if (hasConflict) {
                                Diagnostics.record("SubscriptionsSync", "CONFLICT_SHEET_WON")
                                val closedEpoch = if (!row.active) {
                                    if (existing.active) todayEpochDay else (existing.closedDateEpochDay ?: todayEpochDay)
                                } else {
                                    null
                                }
                                val updatedSub = existing.copy(
                                    name = row.name,
                                    amount = row.amount,
                                    frequency = rowFreq,
                                    anchorEpochDay = resolvedEpochDay,
                                    account = row.account,
                                    active = row.active,
                                    note = row.note,
                                    keywords = row.keywords,
                                    closedDateEpochDay = closedEpoch,
                                    updatedAt = nowMillis
                                )
                                subscriptionRepository.upsertSubscription(updatedSub)
                                roomModified = true
                            }
                            saveBaseHash(row.id, rowHash)
                        } else {
                            saveBaseHash(row.id, rowHash)
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
            val res = googleSheetsClient.writeSubscriptionsTab(spreadsheetId, activeSubs, accounts)
            if (res is Result.Success) {
                for (sub in activeSubs) {
                    saveBaseHash(sub.id, computeSubHash(sub))
                }
            }
            res
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    companion object {
        const val PREFIX_SUB_BASE_HASH = "sub_base_hash_"
    }
}
