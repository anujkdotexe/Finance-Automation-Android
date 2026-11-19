package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.SyncConfigUseCase
import com.financeautomation.domain.usecase.TripSummaryUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

open class TripsSheetSyncCoordinator(
    private val googleSheetsClient: GoogleSheetsClient,
    private val tripTagRepository: TripTagRepository,
    private val preferencesRepository: PreferencesRepository,
    private val transactionRepository: TransactionRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase? = null,
    private val clock: java.time.Clock = java.time.Clock.system(com.financeautomation.core.utils.DateSerialUtils.SHEET_ZONE),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val tripSummaryUseCase = TripSummaryUseCase()
    private val baseHashes = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun getBaseHash(id: String): String? {
        baseHashes[id]?.let { return it }
        val persisted = preferencesRepository.getPreference("$PREFIX_TRIP_BASE_HASH$id")
        if (persisted != null) {
            baseHashes[id] = persisted
        }
        return persisted
    }

    private suspend fun hasBaseHash(id: String): Boolean {
        return baseHashes.containsKey(id) || preferencesRepository.getPreference("$PREFIX_TRIP_BASE_HASH$id") != null
    }

    private suspend fun saveBaseHash(id: String, hash: String) {
        baseHashes[id] = hash
        preferencesRepository.setPreference("$PREFIX_TRIP_BASE_HASH$id", hash, isDirty = false)
    }

    private fun parseDateToEpochDay(dateStr: String): Long? {
        if (dateStr.isBlank()) return null
        val serial = dateStr.toDoubleOrNull()?.toInt()
        if (serial != null && serial > 30000) {
            return runCatching { com.financeautomation.core.utils.DateSerialUtils.fromDateSerial(serial).toEpochDay() }.getOrNull()
        }
        return runCatching { LocalDate.parse(dateStr).toEpochDay() }.getOrNull()
    }

    private fun computeTripHash(name: String, isActive: Boolean, startEpochDay: Long?, endEpochDay: Long?): String =
        "$name|$isActive|$startEpochDay|$endEpochDay"

    private fun computeTripTagHash(trip: TripTag): String =
        computeTripHash(trip.name, trip.isActive, trip.startDateEpochDay, trip.endDateEpochDay)

    /**
     * Synchronizes the visible Trips tab with local Room SQLite.
     * Tab edits update Room, and Room rewrites Config JSON mirror.
     * On same-field conflict between sheet and Room, sheet wins (recorded with code CONFLICT_SHEET_WON).
     */
    open suspend fun syncTripsTab(spreadsheetId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val ensureRes = googleSheetsClient.ensureTripsTabExists(spreadsheetId)
            if (ensureRes is Result.Error) return@withContext Result.Error(ensureRes.exception)

            val readRes = googleSheetsClient.readTripsTab(spreadsheetId)
            if (readRes is Result.Error) return@withContext Result.Error(readRes.exception)

            val sheetRows = (readRes as Result.Success).data
            var roomModified = false
            val processedTripIds = mutableSetOf<String>()
            val todayEpochDay = LocalDate.now(clock).toEpochDay()
            val nowMillis = clock.millis()

            for (row in sheetRows) {
                if (row.name.isBlank() && row.id.isNullOrBlank()) continue

                val parsedStart = parseDateToEpochDay(row.startDateString)
                val parsedEnd = parseDateToEpochDay(row.endDateString)
                val isStatusActive = row.status.equals("Active", ignoreCase = true)

                if (row.id.isNullOrBlank()) {
                    val startEpochDay = if (row.startDateString.isNotBlank() && parsedStart == null) {
                        Diagnostics.record("TripsSync", "REJECTED_BAD_DATE")
                        null
                    } else parsedStart

                    var endEpochDay = if (row.endDateString.isNotBlank() && parsedEnd == null) {
                        Diagnostics.record("TripsSync", "REJECTED_BAD_DATE")
                        null
                    } else parsedEnd

                    val newId = "trip_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    if (!isStatusActive && endEpochDay == null) {
                        endEpochDay = todayEpochDay
                    }
                    val newTrip = TripTag(
                        id = newId,
                        name = row.name,
                        isActive = isStatusActive,
                        isArchived = false,
                        startDateEpochDay = startEpochDay,
                        endDateEpochDay = endEpochDay,
                        createdAt = nowMillis,
                        updatedAt = nowMillis
                    )
                    tripTagRepository.upsertTripTag(newTrip)
                    if (isStatusActive) {
                        tripTagRepository.setActiveTag(newId)
                    }
                    processedTripIds.add(newId)
                    roomModified = true
                    saveBaseHash(newId, computeTripTagHash(newTrip))

                    // Write back new ID to cell K{row.rowNumber}
                    googleSheetsClient.updateCell(
                        spreadsheetId = spreadsheetId,
                        tabName = SheetSchemaConstants.TRIPS_TAB_NAME,
                        cellCoordinate = "${SheetSchemaConstants.COL_LETTER_TRIP_ID}${row.rowNumber}",
                        value = newId
                    )
                } else {
                    val existing = tripTagRepository.getTagById(row.id)
                    if (existing == null) {
                        // If ID was previously synced and tracked in baseHashes, it was deleted in Room: do not resurrect!
                        if (hasBaseHash(row.id)) {
                            Diagnostics.record("TripsSync", "DELETED_ROW_NOT_RESURRECTED")
                            continue
                        }
                        val startEpochDay = if (row.startDateString.isNotBlank() && parsedStart == null) {
                            Diagnostics.record("TripsSync", "REJECTED_BAD_DATE")
                            null
                        } else parsedStart

                        var endEpochDay = if (row.endDateString.isNotBlank() && parsedEnd == null) {
                            Diagnostics.record("TripsSync", "REJECTED_BAD_DATE")
                            null
                        } else parsedEnd

                        if (!isStatusActive && endEpochDay == null) {
                            endEpochDay = todayEpochDay
                        }
                        val newTrip = TripTag(
                            id = row.id,
                            name = row.name,
                            isActive = isStatusActive,
                            isArchived = false,
                            startDateEpochDay = startEpochDay,
                            endDateEpochDay = endEpochDay,
                            createdAt = nowMillis,
                            updatedAt = nowMillis
                        )
                        tripTagRepository.upsertTripTag(newTrip)
                        if (isStatusActive) {
                            tripTagRepository.setActiveTag(row.id)
                        }
                        processedTripIds.add(row.id)
                        roomModified = true
                        saveBaseHash(row.id, computeTripTagHash(newTrip))
                    } else {
                        processedTripIds.add(row.id)

                        val startEpochDay = if (row.startDateString.isNotBlank() && parsedStart == null) {
                            Diagnostics.record("TripsSync", "REJECTED_BAD_DATE")
                            existing.startDateEpochDay
                        } else parsedStart

                        var endEpochDay = if (row.endDateString.isNotBlank() && parsedEnd == null) {
                            Diagnostics.record("TripsSync", "REJECTED_BAD_DATE")
                            existing.endDateEpochDay
                        } else parsedEnd

                        val rowHash = computeTripHash(row.name, isStatusActive, startEpochDay, endEpochDay)
                        val lastBaseHash = getBaseHash(row.id)
                        val sheetChanged = lastBaseHash == null || lastBaseHash != rowHash

                        if (sheetChanged) {
                            val hasConflict = existing.name != row.name ||
                                existing.isActive != isStatusActive ||
                                existing.startDateEpochDay != startEpochDay ||
                                existing.endDateEpochDay != endEpochDay

                            if (hasConflict) {
                                Diagnostics.record("TripsSync", "CONFLICT_SHEET_WON")
                                val resolvedEndEpochDay = if (existing.isActive && !isStatusActive) {
                                    endEpochDay ?: todayEpochDay
                                } else if (!existing.isActive && isStatusActive) {
                                    null
                                } else {
                                    endEpochDay
                                }

                                val updatedTrip = existing.copy(
                                    name = row.name,
                                    isActive = isStatusActive,
                                    startDateEpochDay = startEpochDay,
                                    endDateEpochDay = resolvedEndEpochDay,
                                    updatedAt = nowMillis
                                )
                                tripTagRepository.upsertTripTag(updatedTrip)
                                if (isStatusActive) {
                                    tripTagRepository.setActiveTag(updatedTrip.id)
                                } else if (existing.isActive && !isStatusActive) {
                                    tripTagRepository.setActiveTag(null)
                                }
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
     * Mirrors all Room trips to the Trips tab with calculated settlements and top categories.
     */
    open suspend fun mirrorToSheet(spreadsheetId: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val allTrips = tripTagRepository.getAllTripTags(includeArchived = false)
            val repaidMap = mutableMapOf<String, Double>()
            val topCategoryMap = mutableMapOf<String, String>()

            if (transactionRepository != null) {
                val transactions = transactionRepository.getTransactions().first()
                val summaries = tripSummaryUseCase.byTrip(transactions)
                for (trip in allTrips) {
                    val summary = summaries[trip.name.trim().lowercase()]
                    if (summary != null) {
                        repaidMap[trip.id] = summary.returned
                        topCategoryMap[trip.id] = summary.categoryBreakdown.firstOrNull()?.category.orEmpty()
                    }
                }
            }

            val res = googleSheetsClient.writeTripsTab(spreadsheetId, allTrips, repaidMap, topCategoryMap)
            if (res is Result.Success) {
                for (trip in allTrips) {
                    saveBaseHash(trip.id, computeTripTagHash(trip))
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
        const val PREFIX_TRIP_BASE_HASH = "trip_base_hash_"
    }
}
