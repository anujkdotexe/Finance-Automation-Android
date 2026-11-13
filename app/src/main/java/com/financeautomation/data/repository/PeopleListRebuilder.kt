package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.LedgerSnapshotMapper
import com.financeautomation.data.remote.SheetsReadGate
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.RebuildPeopleListUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Background orchestrator for rebuilding people ranking from the ledger snapshot (Phase 5.7).
 * Reads master ledger snapshot, calculates frequency-weighted companion rankings,
 * and merges discovered names into local storage without removing manually added companions.
 */
class PeopleListRebuilder(
    private val preferencesRepository: PreferencesRepository,
    private val googleSheetsClient: GoogleSheetsClient,
    private val peopleRepository: PeopleRepository,
    private val rebuildPeopleListUseCase: RebuildPeopleListUseCase = RebuildPeopleListUseCase(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readGate: SheetsReadGate = SheetsReadGate(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun refresh(force: Boolean = false): Boolean = withContext(ioDispatcher) {
        try {
            val spreadsheetId = preferencesRepository.getSpreadsheetIdSync() ?: return@withContext false
            // A full read at most once per interval, and none while Google asked the app to wait
            if (!readGate.tryAcquire("people", SheetsReadGate.RECONCILE_INTERVAL_MS, force)) return@withContext false
            val now = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()
            val monthFormatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
            val currentTab = now.format(monthFormatter)
            val prevTab = now.minusMonths(1).format(monthFormatter)
            val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId) {
                it.equals(currentTab, ignoreCase = true) || it.equals(prevTab, ignoreCase = true)
            }
            if (snapRes !is Result.Success) return@withContext false

            val domainSnapshot = LedgerSnapshotMapper.mapToDomain(snapRes.data)

            val rebuilt = rebuildPeopleListUseCase(domainSnapshot, currentTab, prevTab)
            val existing = peopleRepository.getAllCompanions()

            for (name in rebuilt.allNames) {
                if (existing.none { it.name.equals(name, ignoreCase = true) }) {
                    peopleRepository.insertCompanion(
                        Companion(
                            id = UUID.randomUUID().toString(),
                            name = name
                        )
                    )
                }
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("PeopleListRebuilder", "${e.javaClass.simpleName}: refresh failed")
            false
        }
    }
}

