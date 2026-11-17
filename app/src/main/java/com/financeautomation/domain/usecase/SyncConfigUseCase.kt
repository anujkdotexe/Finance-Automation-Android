package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.constants.ConfigConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.safeCall
import com.financeautomation.domain.model.EmptyOrMissingConfigTabException
import com.financeautomation.domain.repository.LegacyConfigGateway
import com.financeautomation.domain.model.ConfigRow
import com.financeautomation.domain.repository.ConfigRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * UseCase coordinating bi-directional synchronization between local Room SQLite
 * and the Google Sheets hidden Config tab (schema v1).
 * Pure Kotlin use case with zero Android framework dependencies.
 */
class SyncConfigUseCase(
    private val configRepository: ConfigRepository,
    private val localGateway: ConfigSyncLocalGateway,
    private val preferencesRepository: PreferencesRepository,
    private val legacyConfigGateway: LegacyConfigGateway? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val pushMutex = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var lastPullTimestamp: Long = 0L

    @Volatile
    var isRemoteSchemaNewerThanSupported: Boolean = false
        private set

    /**
     * Determines whether enough time has passed to trigger an automatic pull on foreground.
     */
    fun shouldPull(throttleMs: Long = 10 * 60 * 1000L): Boolean {
        return System.currentTimeMillis() - lastPullTimestamp > throttleMs
    }

    /**
     * Pulls configuration from the remote Config tab and reconciles with local Room SQLite.
     * Uses timestamp-based last-write-wins conflict resolution.
     */
    suspend fun pull(targetSpreadsheetId: String? = null): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            val spreadsheetId = targetSpreadsheetId ?: preferencesRepository.getSpreadsheetIdSync()
            if (spreadsheetId.isNullOrBlank()) {
                throw IllegalArgumentException("Spreadsheet ID is not configured")
            }

            val readResult = configRepository.readAll(spreadsheetId)
            if (readResult is Result.Error) {
                throw readResult.exception
            }

            val rows = (readResult as Result.Success).data
            if (rows.isEmpty()) {
                // Check if legacy fixed-grid Config!A1:P50 exists for backwards compatibility
                if (legacyConfigGateway != null) {
                    val legacyResult = legacyConfigGateway.readLegacyConfig(spreadsheetId)
                    if (legacyResult is Result.Success) {
                        localGateway.markAllLocalItemsDirty()
                        push(spreadsheetId)
                        lastPullTimestamp = System.currentTimeMillis()
                        return@safeCall
                    }
                }
                throw EmptyOrMissingConfigTabException("Config tab is empty or absent")
            }

            // Inspect schema version
            val metaRow = rows.find { it.type == ConfigConstants.TYPE_META && it.key == ConfigConstants.KEY_SCHEMA_VERSION }
            val remoteVersion = metaRow?.value1?.toIntOrNull() ?: 1
            val supportedVersion = ConfigConstants.CONFIG_SCHEMA_VERSION.toInt()
            if (remoteVersion > supportedVersion) {
                isRemoteSchemaNewerThanSupported = true
            }

            for (row in rows) {
                val epochMilli = row.updatedAt.toEpochMilli()
                when (row.type) {
                    ConfigConstants.TYPE_ACCOUNT -> {
                        val archived = row.value3.equals("true", ignoreCase = true)
                        if (CategoryConstants.isProtectedAccount(row.key) && (row.deleted || archived)) {
                            Diagnostics.record("SyncConfigUseCase", "CONFIG_PROTECTED_ITEM: account")
                            continue
                        }
                        localGateway.applyRemoteAccount(
                            name = row.key,
                            lastFour = row.value1.ifEmpty { null },
                            isDefault = row.value2.equals("true", ignoreCase = true),
                            isArchived = row.value3.equals("true", ignoreCase = true),
                            displayOrder = row.value4.toIntOrNull() ?: 0,
                            updatedAt = epochMilli,
                            deleted = row.deleted
                        )
                    }
                    ConfigConstants.TYPE_CATEGORY -> {
                        if (CategoryConstants.isProtectedCategory(row.key) &&
                            (row.deleted || row.value3.equals("true", ignoreCase = true))
                        ) {
                            Diagnostics.record("SyncConfigUseCase", "CONFIG_PROTECTED_ITEM: category")
                            continue
                        }
                        val parts = row.value4.split("|")
                        val displayOrder = parts[0].toIntOrNull() ?: 0
                        val colorName = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "Teal"
                        localGateway.applyRemoteCategory(
                            name = row.key,
                            excludedFromBurn = row.value1.equals("true", ignoreCase = true),
                            isDefault = row.value2.equals("true", ignoreCase = true),
                            isArchived = row.value3.equals("true", ignoreCase = true),
                            displayOrder = displayOrder,
                            updatedAt = epochMilli,
                            deleted = row.deleted,
                            iconName = colorName
                        )
                    }
                    ConfigConstants.TYPE_PERSON -> {
                        localGateway.applyRemoteCompanion(
                            name = row.key,
                            isArchived = row.value2.equals("true", ignoreCase = true),
                            updatedAt = epochMilli,
                            deleted = row.deleted
                        )
                    }
                    ConfigConstants.TYPE_TRIP -> {
                        localGateway.applyRemoteTripTag(
                            name = row.key,
                            isActive = row.value1.equals("true", ignoreCase = true),
                            isArchived = row.value2.equals("true", ignoreCase = true),
                            updatedAt = epochMilli,
                            deleted = row.deleted
                        )
                    }
                    ConfigConstants.TYPE_SUBSCRIPTION -> {
                        val subscription = SubscriptionConfigCodec.decode(row.key, row.value1)
                        if (subscription != null) {
                            localGateway.applyRemoteSubscription(
                                subscription = subscription,
                                updatedAt = epochMilli,
                                deleted = row.deleted
                            )
                        }
                    }
                    ConfigConstants.TYPE_BUDGET -> {
                        val amount = row.value1.replace(",", "").trim().toDoubleOrNull()
                        if (row.key.isNotBlank() && amount != null && amount > 0.0) {
                            localGateway.applyRemoteBudget(
                                category = row.key,
                                monthlyAmount = amount,
                                updatedAt = epochMilli,
                                deleted = row.deleted
                            )
                        } else if (row.key.isNotBlank() && row.deleted) {
                            localGateway.applyRemoteBudget(
                                category = row.key,
                                monthlyAmount = 0.0,
                                updatedAt = epochMilli,
                                deleted = true
                            )
                        }
                    }
                    ConfigConstants.TYPE_PREF -> {
                        if (!ConfigConstants.isLocalOnlyPref(row.key)) {
                            localGateway.applyRemotePreference(
                                key = row.key,
                                value = row.value1.ifEmpty { null },
                                updatedAt = epochMilli,
                                deleted = row.deleted
                            )
                        }
                    }
                }
            }

            lastPullTimestamp = System.currentTimeMillis()
        }
    }

    /**
     * Pushes locally modified items (dirty = 1) to the remote Config tab.
     */
    suspend fun push(targetSpreadsheetId: String? = null): Result<Unit> = withContext(ioDispatcher) {
        // One push at a time: the queued push and an immediate push must not append the same Config row twice
        pushMutex.withLock { safeCall {
            val spreadsheetId = targetSpreadsheetId ?: preferencesRepository.getSpreadsheetIdSync()
            if (spreadsheetId.isNullOrBlank()) {
                throw IllegalArgumentException("Spreadsheet ID is not configured")
            }

            if (isRemoteSchemaNewerThanSupported) {
                throw IllegalStateException("Remote schema version is newer than supported. Please update the application.")
            }

            val deviceId = preferencesRepository.getOrCreateDeviceId()
            val dirty = localGateway.getDirtySnapshot()
            if (dirty.isEmpty) {
                return@safeCall
            }

            val now = Instant.now()
            val dirtyRows = mutableListOf<ConfigRow>()

            dirty.accounts.forEach {
                val entityTime = if (it.updatedAt > 0L) Instant.ofEpochMilli(it.updatedAt) else now
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_ACCOUNT,
                        key = it.name,
                        values = listOf(it.lastFourDigits.orEmpty(), it.isDefault.toString(), it.isArchived.toString(), it.displayOrder.toString()),
                        updatedAt = entityTime,
                        updatedBy = deviceId,
                        deleted = it.deleted
                    )
                )
            }

            dirty.categories.forEach {
                val entityTime = if (it.updatedAt > 0L) Instant.ofEpochMilli(it.updatedAt) else now
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_CATEGORY,
                        key = it.name,
                        values = listOf(it.isExcludedFromLivingBurn.toString(), it.isDefault.toString(), it.isArchived.toString(), "${it.displayOrder}|${it.iconName}"),
                        updatedAt = entityTime,
                        updatedBy = deviceId,
                        deleted = it.deleted
                    )
                )
            }

            dirty.companions.forEach {
                val entityTime = if (it.updatedAt > 0L) Instant.ofEpochMilli(it.updatedAt) else now
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_PERSON,
                        key = it.name,
                        values = listOf("0", it.isArchived.toString()),
                        updatedAt = entityTime,
                        updatedBy = deviceId,
                        deleted = it.deleted
                    )
                )
            }

            dirty.tripTags.forEach {
                val entityTime = if (it.updatedAt > 0L) Instant.ofEpochMilli(it.updatedAt) else now
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_TRIP,
                        key = it.name,
                        values = listOf(it.isActive.toString(), it.isArchived.toString()),
                        updatedAt = entityTime,
                        updatedBy = deviceId,
                        deleted = it.deleted
                    )
                )
            }

            dirty.subscriptions.forEach {
                val entityTime = if (it.updatedAt > 0L) Instant.ofEpochMilli(it.updatedAt) else now
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_SUBSCRIPTION,
                        key = it.id,
                        values = listOf(SubscriptionConfigCodec.encode(it)),
                        updatedAt = entityTime,
                        updatedBy = deviceId,
                        deleted = it.deleted
                    )
                )
            }

            dirty.budgets.forEach {
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_BUDGET,
                        key = it.category,
                        values = listOf(formatBudgetAmount(it.monthlyAmount)),
                        updatedAt = Instant.ofEpochMilli(it.updatedAt),
                        updatedBy = deviceId,
                        deleted = it.deleted
                    )
                )
            }

            dirty.preferences.forEach {
                dirtyRows.add(
                    ConfigRow(
                        type = ConfigConstants.TYPE_PREF,
                        key = it.key,
                        values = listOf(it.value.orEmpty()),
                        updatedAt = Instant.ofEpochMilli(it.updatedAt),
                        updatedBy = deviceId,
                        deleted = it.value == null
                    )
                )
            }

            // Tombstone any leaked entity tokens (txn_, pay_, inf_, row_) present in remote Config tab
            val remoteResult = configRepository.readAll(spreadsheetId)
            if (remoteResult is Result.Success) {
                for (remoteRow in remoteResult.data) {
                    if (remoteRow.type == ConfigConstants.TYPE_PERSON &&
                        !remoteRow.deleted &&
                        (remoteRow.key.startsWith("txn_", ignoreCase = true) ||
                         remoteRow.key.startsWith("pay_", ignoreCase = true) ||
                         remoteRow.key.startsWith("inf_", ignoreCase = true) ||
                         remoteRow.key.startsWith("row_", ignoreCase = true))
                    ) {
                        dirtyRows.add(
                            ConfigRow(
                                type = ConfigConstants.TYPE_PERSON,
                                key = remoteRow.key,
                                values = listOf("", ""),
                                updatedAt = now,
                                updatedBy = deviceId,
                                deleted = true
                            )
                        )
                    }
                }
            }

            val upsertResult = configRepository.upsert(spreadsheetId, dirtyRows)
            if (upsertResult is Result.Error) {
                throw upsertResult.exception
            }

            // Clear dirty flags only for items the sheet really took. A row the sheet already holds in a newer
            // version was skipped: it stays dirty and the next pull brings the sheet value down.
            val written = (upsertResult as Result.Success).data
            fun isWritten(type: String, key: String) = ConfigRow.compositeKeyOf(type, key) in written
            localGateway.markAccountsClean(dirty.accounts.filter { isWritten(ConfigConstants.TYPE_ACCOUNT, it.name) }.map { it.id })
            localGateway.markCategoriesClean(dirty.categories.filter { isWritten(ConfigConstants.TYPE_CATEGORY, it.name) }.map { it.id })
            localGateway.markCompanionsClean(dirty.companions.filter { isWritten(ConfigConstants.TYPE_PERSON, it.name) }.map { it.id })
            localGateway.markTripTagsClean(dirty.tripTags.filter { isWritten(ConfigConstants.TYPE_TRIP, it.name) }.map { it.id })
            localGateway.markPreferencesClean(dirty.preferences.filter { isWritten(ConfigConstants.TYPE_PREF, it.key) }.map { it.key })
            localGateway.markSubscriptionsClean(dirty.subscriptions.filter { isWritten(ConfigConstants.TYPE_SUBSCRIPTION, it.id) }.map { it.id })
            localGateway.markBudgetsClean(dirty.budgets.filter { isWritten(ConfigConstants.TYPE_BUDGET, it.category) }.map { it.category })
        }
    } }

    /**
     * Marks all local items dirty and pushes them to populate an initially empty Config tab.
     */
    suspend fun pushLocalDefaults(targetSpreadsheetId: String? = null): Result<Unit> = withContext(ioDispatcher) {
        safeCall {
            localGateway.markAllLocalItemsDirty()
            val pushRes = push(targetSpreadsheetId)
            if (pushRes is Result.Error) {
                throw pushRes.exception
            }
        }
    }

    private fun formatBudgetAmount(amount: Double): String =
        java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()

    /**
     * Backward-compatible alias for setup/onboarding pull.
     */
    suspend fun restoreFromCloud(targetSpreadsheetId: String? = null): Result<Unit> {
        return pull(targetSpreadsheetId)
    }

    /**
     * Backward-compatible alias for setup/onboarding push.
     */
    suspend fun mirrorToCloud(targetSpreadsheetId: String? = null): Result<Unit> {
        return pushLocalDefaults(targetSpreadsheetId)
    }
}
