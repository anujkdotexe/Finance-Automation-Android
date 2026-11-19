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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private val tripSummaryUseCase = TripSummaryUseCase()

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

            for (row in sheetRows) {
                if (row.name.isBlank() && row.id.isNullOrBlank()) continue

                val startEpochDay = runCatching { LocalDate.parse(row.startDateString).toEpochDay() }.getOrNull()
                var endEpochDay = runCatching { LocalDate.parse(row.endDateString).toEpochDay() }.getOrNull()
                val isStatusActive = row.status.equals("Active", ignoreCase = true)

                if (row.id.isNullOrBlank()) {
                    // New row added on sheet without ID: generate UUID, upsert to Room, and write back ID to sheet
                    val newId = "trip_" + UUID.randomUUID().toString().replace("-", "").take(12)
                    if (!isStatusActive && endEpochDay == null) {
                        endEpochDay = LocalDate.now().toEpochDay()
                    }
                    val newTrip = TripTag(
                        id = newId,
                        name = row.name,
                        isActive = isStatusActive,
                        isArchived = false,
                        startDateEpochDay = startEpochDay,
                        endDateEpochDay = endEpochDay,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    tripTagRepository.upsertTripTag(newTrip)
                    if (isStatusActive) {
                        tripTagRepository.setActiveTag(newId)
                    }
                    processedTripIds.add(newId)
                    roomModified = true

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
                        // ID present on sheet but missing in Room: insert
                        if (!isStatusActive && endEpochDay == null) {
                            endEpochDay = LocalDate.now().toEpochDay()
                        }
                        val newTrip = TripTag(
                            id = row.id,
                            name = row.name,
                            isActive = isStatusActive,
                            isArchived = false,
                            startDateEpochDay = startEpochDay,
                            endDateEpochDay = endEpochDay,
                            createdAt = System.currentTimeMillis(),
                            updatedAt = System.currentTimeMillis()
                        )
                        tripTagRepository.upsertTripTag(newTrip)
                        if (isStatusActive) {
                            tripTagRepository.setActiveTag(row.id)
                        }
                        processedTripIds.add(row.id)
                        roomModified = true
                    } else {
                        processedTripIds.add(row.id)
                        // Check for field conflicts
                        val hasConflict = existing.name != row.name ||
                            existing.isActive != isStatusActive ||
                            existing.startDateEpochDay != startEpochDay ||
                            existing.endDateEpochDay != endEpochDay

                        if (hasConflict) {
                            // Sheet wins!
                            Diagnostics.record("TripsSync", "CONFLICT_SHEET_WON")
                            // If ending a trip, set end date if unset; if reopening, clear end date
                            val resolvedEndEpochDay = if (existing.isActive && !isStatusActive) {
                                endEpochDay ?: LocalDate.now().toEpochDay()
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
                                updatedAt = System.currentTimeMillis()
                            )
                            tripTagRepository.upsertTripTag(updatedTrip)
                            if (isStatusActive) {
                                tripTagRepository.setActiveTag(updatedTrip.id)
                            } else if (existing.isActive && !isStatusActive) {
                                tripTagRepository.setActiveTag(null)
                            }
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

            googleSheetsClient.writeTripsTab(spreadsheetId, allTrips, repaidMap, topCategoryMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
