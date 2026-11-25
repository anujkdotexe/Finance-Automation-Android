package com.financeautomation.data.remote

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.getByTabAndSheetRowIndex
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import java.time.Instant
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CarryForwardRequest
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LogCreditRequest
import com.financeautomation.domain.model.LogPayableRequest
import com.financeautomation.domain.model.LogRepaymentRequest
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.model.YearCloseRequest
import com.financeautomation.domain.usecase.CarryForwardUseCase
import com.financeautomation.domain.usecase.NettingUseCase
import com.financeautomation.domain.usecase.RepaymentWaterfallUseCase
import com.financeautomation.domain.usecase.SettlementWaterfallUseCase
import com.financeautomation.domain.usecase.VoidTransactionUseCase
import com.financeautomation.domain.usecase.YearCloseUseCase
import com.financeautomation.domain.utils.AccountingUtils
import java.util.Locale
import java.util.UUID

private val MONTH_TAB_REGEX = Regex("""^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(\d{2})$""", RegexOption.IGNORE_CASE)

data class PayableOutcome(
    val netAmount: Double,
    val fullyRepaid: Boolean,
    val dateSerial: Int?
)

/**
 * Handles two-phase durable execution of complex accounting operations in the sync queue:
 * Friend settlements (OP_LOG_CREDIT), payables (OP_LOG_PAYABLE), repayments (OP_LOG_REPAYMENT),
 * monthly rollover (OP_CARRY_FORWARD), and annual closing (OP_YEAR_CLOSE).
 */
open class AccountingSyncExecutor(
    private val googleSheetsClient: GoogleSheetsClient,
    private val googleDriveClient: GoogleDriveClient,
    private val syncQueueDao: SyncQueueDao? = null,
    private val lineageDao: LineageDao? = null,
    private val transactionDao: TransactionDao? = null,
    private val inflowDao: InflowDao? = null,
    private val payableDao: PayableDao? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val lineageDaoProvider: (suspend () -> LineageDao)? = null,
    private val transactionDaoProvider: (suspend () -> TransactionDao)? = null,
    private val inflowDaoProvider: (suspend () -> InflowDao)? = null,
    private val payableDaoProvider: (suspend () -> PayableDao)? = null,
    private val accountsProvider: (suspend () -> List<String>)? = null,
    private val categoriesProvider: (suspend () -> List<String>)? = null,
    private val settlementWaterfallUseCase: SettlementWaterfallUseCase = SettlementWaterfallUseCase(),
    private val repaymentWaterfallUseCase: RepaymentWaterfallUseCase = RepaymentWaterfallUseCase(),
    private val carryForwardUseCase: CarryForwardUseCase = CarryForwardUseCase(),
    private val nettingUseCase: NettingUseCase = NettingUseCase(),
    private val yearCloseUseCase: YearCloseUseCase = YearCloseUseCase(),
    private val voidTransactionUseCase: VoidTransactionUseCase = VoidTransactionUseCase(),
    private val previousWorkbookIdProvider: (suspend (year: Int) -> String?)? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    constructor(
        googleSheetsClient: GoogleSheetsClient,
        googleDriveClient: GoogleDriveClient,
        syncQueueDao: SyncQueueDao,
        lineageDao: LineageDao,
        settlementWaterfallUseCase: SettlementWaterfallUseCase = SettlementWaterfallUseCase(),
        repaymentWaterfallUseCase: RepaymentWaterfallUseCase = RepaymentWaterfallUseCase(),
        carryForwardUseCase: CarryForwardUseCase = CarryForwardUseCase(),
        nettingUseCase: NettingUseCase = NettingUseCase(),
        yearCloseUseCase: YearCloseUseCase = YearCloseUseCase()
    ) : this(
        googleSheetsClient = googleSheetsClient,
        googleDriveClient = googleDriveClient,
        syncQueueDao = syncQueueDao,
        lineageDao = lineageDao,
        transactionDao = null,
        inflowDao = null,
        payableDao = null,
        syncQueueDaoProvider = null,
        lineageDaoProvider = null,
        transactionDaoProvider = null,
        inflowDaoProvider = null,
        payableDaoProvider = null,
        accountsProvider = null,
        categoriesProvider = null,
        settlementWaterfallUseCase = settlementWaterfallUseCase,
        repaymentWaterfallUseCase = repaymentWaterfallUseCase,
        carryForwardUseCase = carryForwardUseCase,
        nettingUseCase = nettingUseCase,
        yearCloseUseCase = yearCloseUseCase
    )

    private suspend fun resolveSyncQueueDao(): SyncQueueDao =
        syncQueueDao ?: syncQueueDaoProvider?.invoke()
            ?: throw IllegalStateException("SyncQueueDao or provider must be supplied")

    private suspend fun resolveLineageDao(): LineageDao =
        lineageDao ?: lineageDaoProvider?.invoke()
            ?: throw IllegalStateException("LineageDao or provider must be supplied")

    private suspend fun resolveTransactionDao(): TransactionDao? =
        transactionDao ?: transactionDaoProvider?.invoke()

    private suspend fun resolveInflowDao(): InflowDao? =
        inflowDao ?: inflowDaoProvider?.invoke()

    private suspend fun resolvePayableDao(): PayableDao? =
        payableDao ?: payableDaoProvider?.invoke()

    suspend fun executeLogCredit(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<Unit> {
        val plan: AccountingPlan
        if (item.planJson != null) {
            plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
        } else {
            val req = AccountingRequestJsonAdapter.creditRequestFromJson(item.payloadJson)
            val dateSerial = DateSerialUtils.toDateSerial(req.timestamp)
            val timeSerial = DateSerialUtils.toTimeSerial(req.timestamp)

            val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
            if (snapRes is Result.Error) {
                return Result.Error(snapRes.exception)
            }
            val allDebits = mapDebitsFromSnapshot((snapRes as Result.Success).data)

            val lineageRecords = resolveLineageDao().getAll().map { it.toDomain() }
            val resolveCarriedFn: (LedgerDebit) -> LedgerDebit? = { carriedRow ->
                carryForwardUseCase.resolveCarriedDebit(carriedRow, allDebits, lineageRecords)
            }

            plan = settlementWaterfallUseCase(
                settler = req.settler,
                amount = req.amount,
                explicitRefs = req.explicitRefs,
                creditDateSerial = dateSerial,
                creditTimeSerial = timeSerial,
                account = req.account,
                inflowType = req.inflowType,
                reason = req.reason,
                tabName = tabName,
                allDebits = allDebits,
                resolveCarriedFn = resolveCarriedFn
            ).withInflowUuid(item.transactionId)

            val planJson = AccountingPlanJsonAdapter.toJson(plan)
            resolveSyncQueueDao().updatePlanJson(item.id, planJson)
        }

        return applyAccountingPlan(spreadsheetId, plan)
    }

    /**
     * The use case generates a random id for the Table 2 row. Store the credit request id instead, so the row can be
     * found again later (for example to void it). Plans cached before this change keep their random id.
     */
    private fun AccountingPlan.withInflowUuid(inflowId: String): AccountingPlan = withInflowRowUuid(inflowId)

    /**
     * Voids the Table 2 row written for the given income entry, found by its id in column AD. Zeroes the amount and
     * prefixes the reason with "[Void] ", the same convention as a void debit. A row that is not found is an error
     * that is not retried (for example an entry written by an older build, which stored a different id).
     */
    suspend fun executeVoidInflow(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<Unit> {
        val req = AccountingRequestJsonAdapter.voidInflowRequestFromJson(item.payloadJson)
        var isV1 = false
        var targetTab = tabName
        // New layout id column first, then the older layout one (same order as the restore path)
        val rowRes = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_INFLOW_UUID, req.inflowId)
            .let { res ->
                if (res is Result.Success && res.data == null) {
                    val v1 = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_INFLOW_UUID_V1, req.inflowId)
                    if (v1 is Result.Success && v1.data != null) {
                        isV1 = true
                    }
                    v1
                } else res
            }
        if (rowRes is Result.Error) return Result.Error(rowRes.exception)
        var row = (rowRes as Result.Success).data

        if (row == null) {
            val tabsRes = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
            if (tabsRes is Result.Success) {
                for (other in tabsRes.data.keys.filter { it != tabName && MONTH_TAB_REGEX.matches(it) }) {
                    val r = googleSheetsClient.findRowByUuid(spreadsheetId, other, SheetSchemaConstants.COL_LETTER_INFLOW_UUID, req.inflowId)
                    if (r is Result.Success && r.data != null) {
                        targetTab = other
                        row = r.data
                        break
                    }
                    val rV1 = googleSheetsClient.findRowByUuid(spreadsheetId, other, SheetSchemaConstants.COL_LETTER_INFLOW_UUID_V1, req.inflowId)
                    if (rV1 is Result.Success && rV1.data != null) {
                        targetTab = other
                        row = rV1.data
                        isV1 = true
                        break
                    }
                }
            }
        }

        if (row == null) {
            if (req.deleteSheetRow) {
                resolveInflowDao()?.deleteById(req.inflowId)
                return Result.Success(Unit)
            }
            return Result.Error(IllegalStateException("Income row not found in $tabName for ${req.inflowId}"))
        }

        if (req.deleteSheetRow) {
            val inflowRange = if (isV1) "'$targetTab'!${SheetSchemaConstants.TABLE_2_INFLOWS_RANGE_V1.substringBefore(':')}$row:${SheetSchemaConstants.TABLE_2_INFLOWS_RANGE_V1.substringAfter(':')}$row"
                              else "'$targetTab'!${SheetSchemaConstants.COL_LETTER_INFLOW_DATE}$row:${SheetSchemaConstants.COL_LETTER_INFLOW_LINKED_DEBIT}$row"
            val idRange = if (isV1) "'$targetTab'!${SheetSchemaConstants.COL_LETTER_INFLOW_UUID_V1}$row"
                          else "'$targetTab'!${SheetSchemaConstants.COL_LETTER_INFLOW_UUID}$row"

            val clearRes = googleSheetsClient.batchClearValues(spreadsheetId, listOf(inflowRange, idRange))
            if (clearRes is Result.Error) return Result.Error(clearRes.exception)
            resolveInflowDao()?.deleteById(req.inflowId)
            return Result.Success(Unit)
        }

        val reasonRes = googleSheetsClient.getCellString(spreadsheetId, targetTab, "${SheetSchemaConstants.COL_LETTER_INFLOW_REASON}$row")
        if (reasonRes is Result.Error) return Result.Error(reasonRes.exception)
        val currentReason = (reasonRes as Result.Success).data.orEmpty()

        val plan = voidTransactionUseCase.voidInflow(targetTab, row, currentReason)
        val updates = plan.edits.associate { edit ->
            "'${edit.tab}'!${edit.column}${edit.row}" to listOf(listOf(edit.value))
        }
        return googleSheetsClient.batchUpdateValues(spreadsheetId, updates)
    }

    suspend fun executeLogPayable(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<PayableOutcome> {
        val plan: AccountingPlan
        val outcome: PayableOutcome
        if (item.planJson != null) {
            plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
            val payAppend = plan.appends.find { it.table == TableType.PAYABLE }
            val netAmount = (payAppend?.cells?.getOrNull(4) as? Number)?.toDouble() ?: 0.0
            val fullyRepaid = (payAppend?.cells?.getOrNull(6) as? String)?.equals("Repaid", ignoreCase = true) == true
            val dateSerial = (payAppend?.cells?.getOrNull(0) as? Number)?.toInt()
            outcome = PayableOutcome(netAmount, fullyRepaid, dateSerial)
        } else {
            val req = AccountingRequestJsonAdapter.payableRequestFromJson(item.payloadJson)
            val dateSerial = DateSerialUtils.toDateSerial(req.timestamp)
            val timeSerial = DateSerialUtils.toTimeSerial(req.timestamp)

            val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
            if (snapRes is Result.Error) {
                return Result.Error(snapRes.exception)
            }
            val allDebits = mapDebitsFromSnapshot((snapRes as Result.Success).data)

            val nettingRes = nettingUseCase.nettingOnPayable(
                creditor = req.creditor,
                amount = req.amount,
                debits = allDebits,
                settleOneDebtFn = { row, paid, settler, allowFull ->
                    settlementWaterfallUseCase.settleOneDebt(row, paid, settler, allowFull)
                }
            )

            val newPayUuid = req.requestId
            val isFullyRepaid = nettingRes.netAmount <= 0.0
            val effectiveCategory = req.category.trim().ifBlank { com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER }
            val effectiveWhatFor = com.financeautomation.core.utils.PayableFormatUtils.buildEffectiveWhatFor(req.whatFor, effectiveCategory)
            val payCells = listOf(
                dateSerial,
                timeSerial,
                req.creditor,
                effectiveWhatFor,
                AccountingUtils.round2(nettingRes.netAmount),
                if (isFullyRepaid) dateSerial else "",
                if (isFullyRepaid) "Repaid" else "Pending"
            )

            val newDebitUuid = req.transactionId ?: ("txn_" + UUID.randomUUID().toString().replace("-", "").take(12))
            val debitCells = listOf(
                dateSerial,
                timeSerial,
                "Other",
                if (req.whatFor.isNotBlank()) req.whatFor else "Paid by ${req.creditor}",
                "",
                effectiveCategory,
                AccountingUtils.round2(req.amount),
                "Personal",
                AccountingUtils.round2(req.amount),
                0.0,
                "Personal"
            )

            val appends = listOf(
                com.financeautomation.domain.model.NewRow(
                    table = TableType.DEBIT,
                    tab = tabName,
                    uuid = newDebitUuid,
                    cells = debitCells
                ),
                com.financeautomation.domain.model.NewRow(
                    table = TableType.PAYABLE,
                    tab = tabName,
                    uuid = newPayUuid,
                    cells = payCells
                )
            )

            plan = AccountingPlan(
                edits = nettingRes.edits,
                appends = appends,
                excess = 0.0,
                unmatched = false,
                message = "Payable logged (₹${nettingRes.offsetTotal} offset against existing debits)"
            )

            val planJson = AccountingPlanJsonAdapter.toJson(plan)
            resolveSyncQueueDao().updatePlanJson(item.id, planJson)
            outcome = PayableOutcome(
                netAmount = AccountingUtils.round2(nettingRes.netAmount),
                fullyRepaid = isFullyRepaid,
                dateSerial = if (isFullyRepaid) dateSerial else null
            )
        }

        val applyResult = applyAccountingPlan(spreadsheetId, plan)
        return when (applyResult) {
            is Result.Success -> Result.Success(outcome)
            is Result.Error -> Result.Error(applyResult.exception)
            is Result.Loading -> Result.Loading
        }
    }

    suspend fun executeLogRepayment(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<Unit> {
        val plan: AccountingPlan
        if (item.planJson != null) {
            plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
        } else {
            val req = AccountingRequestJsonAdapter.repaymentRequestFromJson(item.payloadJson)
            val dateSerial = DateSerialUtils.toDateSerial(req.timestamp)
            val timeSerial = DateSerialUtils.toTimeSerial(req.timestamp)

            val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
            if (snapRes is Result.Error) {
                return Result.Error(snapRes.exception)
            }
            val allPayables = mapPayablesFromSnapshot((snapRes as Result.Success).data)

            val lineageRecords = resolveLineageDao().getAll().map { it.toDomain() }
            val resolveCarriedFn: (LedgerPayable) -> LedgerPayable? = { carriedRow ->
                carryForwardUseCase.resolveCarriedPayable(carriedRow, allPayables, lineageRecords)
            }

            plan = repaymentWaterfallUseCase(
                creditor = req.creditor,
                amount = req.amount,
                explicitRef = req.explicitRef,
                dateSerial = dateSerial,
                timeSerial = timeSerial,
                tabName = tabName,
                allPayables = allPayables,
                resolveCarriedFn = resolveCarriedFn,
                account = req.account,
                note = req.note
            )

            val planJson = AccountingPlanJsonAdapter.toJson(plan)
            resolveSyncQueueDao().updatePlanJson(item.id, planJson)
        }

        return applyAccountingPlan(spreadsheetId, plan)
    }

    suspend fun executeCarryForward(
        item: SyncQueueEntity,
        spreadsheetId: String
    ): Result<Unit> {
        val req = AccountingRequestJsonAdapter.carryForwardRequestFromJson(item.payloadJson)
        val targetTab = req.targetTab

        // 1. Ensure target tab exists with accounts and categories dropdowns
        val accounts = if (req.accounts.isNotEmpty()) req.accounts else accountsProvider?.invoke().orEmpty()
        val categories = if (req.categories.isNotEmpty()) req.categories else categoriesProvider?.invoke().orEmpty()
        val tabEnsureRes = googleSheetsClient.ensureMonthTabExists(spreadsheetId, targetTab, accounts, categories)
        if (tabEnsureRes is Result.Error) {
            return Result.Error(tabEnsureRes.exception)
        }

        // 1b. Guard: move debts only AFTER the new month starts in Asia/Kolkata.
        // If targetTab is in a future month, the tab is ensured above, but debts stay open in the current month.
        val targetKey = parseMonthKey(targetTab)
        if (targetKey != null) {
            val currentKolkata = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneId.of("Asia/Kolkata"))
            val currentKey = currentKolkata.year * 12 + (currentKolkata.monthValue - 1)
            if (targetKey > currentKey) {
                return Result.Success(Unit)
            }
        }

        // 2. Read snapshot of workbook
        val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
        if (snapRes is Result.Error) return Result.Error(snapRes.exception)
        val snapshot = (snapRes as Result.Success).data

        var sourceTab = req.sourceTab ?: findPrecedingTab(targetTab, snapshot.monthTabs.map { it.tabName })
        var sourceSnapshot: LedgerWorkbookSnapshot = snapshot
        var sourceSpreadsheetId: String = spreadsheetId

        val sourceKey = sourceTab?.let { parseMonthKey(it) }

        // If source tab is in the previous year (e.g. Dec26 -> Jan27), source snapshot must come from previous year's workbook
        val isTargetJan = targetKey != null && (targetKey % 12 == 0)
        val isCrossYear = targetKey != null && (sourceTab == null || (sourceKey != null && sourceKey / 12 < targetKey / 12))
        if (isTargetJan && isCrossYear) {
            val targetYear = targetKey!! / 12
            val prevYear = targetYear - 1
            val prevSpreadsheetId = previousWorkbookIdProvider?.invoke(prevYear)
            if (prevSpreadsheetId.isNullOrBlank()) {
                return Result.Error(IllegalStateException("Previous year ($prevYear) workbook ID not found for carry forward to $targetTab"))
            }
            val prevSnapRes = googleSheetsClient.readLedgerSnapshot(prevSpreadsheetId)
            if (prevSnapRes is Result.Error) {
                return Result.Error(prevSnapRes.exception)
            }
            val prevSnap = (prevSnapRes as Result.Success).data
            val prevDecTab = "Dec${(prevYear % 100).toString().padStart(2, '0')}"
            if (prevSnap.monthTabs.isEmpty()) {
                return Result.Error(IllegalStateException("No source month tab found in previous year ($prevYear) workbook for carry forward"))
            }
            sourceTab = prevSnap.monthTabs.find { it.tabName.equals(sourceTab ?: prevDecTab, ignoreCase = true) }?.tabName
            sourceSnapshot = prevSnap
            sourceSpreadsheetId = prevSpreadsheetId
        }

        // Every month before the target can still hold open rows when carries were missed for one or more whole
        // months, so each of them is a source, oldest first. This uses the snapshot already read.
        val sourceMonths = sourceSnapshot.monthTabs
            .filter { month ->
                if (month.tabName.equals(targetTab, ignoreCase = true)) return@filter false
                if (month.tabName.equals(sourceTab, ignoreCase = true)) return@filter true
                val key = parseMonthKey(month.tabName)
                targetKey != null && key != null && key < targetKey
            }
            .sortedBy { parseMonthKey(it.tabName) ?: 0 }

        val existingLineage = resolveLineageDao().getAll().map { it.toDomain() }
        val appends = mutableListOf<com.financeautomation.domain.model.NewRow>()
        val edits = mutableListOf<com.financeautomation.domain.model.CellEdit>()
        val newLineage = mutableListOf<com.financeautomation.domain.model.LineageRecord>()
        for (sourceMonth in sourceMonths) {
            // A copy of one of this month's rows can only sit in a later month, up to the target
            val sourceMonthKey = parseMonthKey(sourceMonth.tabName) ?: 0
            val copyMonths = (snapshot.monthTabs + sourceSnapshot.monthTabs)
                .distinctBy { it.tabName.lowercase(Locale.US) }
                .filter { month ->
                    val key = parseMonthKey(month.tabName)
                    key != null && key > sourceMonthKey && (targetKey == null || key <= targetKey)
                }
            val result = carryForwardUseCase(
                targetTab = targetTab,
                sourceTab = sourceMonth.tabName,
                targetDebits = copyMonths.flatMap { m -> m.debits.map { it.toDomain(m.tabName) } },
                targetPayables = copyMonths.flatMap { m -> m.payables.map { it.toDomain(m.tabName) } },
                sourceDebits = sourceMonth.debits.map { it.toDomain(sourceMonth.tabName) },
                sourcePayables = sourceMonth.payables.map { it.toDomain(sourceMonth.tabName) },
                existingLineage = existingLineage
            )
            appends.addAll(result.plan.appends)
            edits.addAll(result.plan.edits)
            newLineage.addAll(result.lineageRecords)
        }

        if (appends.isEmpty() && edits.isEmpty()) {
            return Result.Success(Unit)
        }

        // Apply plan: appends to target, source status edits to source (or target) workbook
        val applyResult = applyAccountingPlan(
            spreadsheetId = spreadsheetId,
            plan = AccountingPlan(edits = edits, appends = appends),
            sourceSpreadsheetId = sourceSpreadsheetId
        )
        if (applyResult is Result.Success) {
            // Persist lineage records into Room in one transaction
            if (newLineage.isNotEmpty()) {
                resolveLineageDao().insertAll(newLineage.map { LineageEntity.fromDomain(it) })
            }
        }
        return applyResult
    }

    suspend fun executeYearClose(
        item: SyncQueueEntity,
        spreadsheetId: String
    ): Result<Unit> {
        val req = AccountingRequestJsonAdapter.yearCloseRequestFromJson(item.payloadJson)
        val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
        if (snapRes is Result.Error) return Result.Error(snapRes.exception)
        val snapshot = (snapRes as Result.Success).data

        val allDebits = mapDebitsFromSnapshot(snapshot)
        val allPayables = mapPayablesFromSnapshot(snapshot)

        val statement = yearCloseUseCase(req.year, allDebits, allPayables)

        // 1. Ensure CarryFwd tab exists
        val tabsRes = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
        if (tabsRes is Result.Success) {
            if (!tabsRes.data.containsKey("CarryFwd")) {
                val addSheetReq = mapOf(
                    "addSheet" to mapOf(
                        "properties" to mapOf("title" to "CarryFwd")
                    )
                )
                googleSheetsClient.batchUpdate(spreadsheetId, listOf(addSheetReq))
            }
        }

        // 2. Write statement rows to CarryFwd tab
        val valuesMap = mapOf(
            "'CarryFwd'!A1" to statement.carryFwdSheetRows
        )
        val updateRes = googleSheetsClient.batchUpdateValues(spreadsheetId, valuesMap)
        if (updateRes is Result.Error) return Result.Error(updateRes.exception)

        // 3. Upload carry_forward_<year>.json to Google Drive folder
        val folderResult = googleDriveClient.findOrCreateFolder("FinanceAutomation")
        if (folderResult is Result.Error) {
            return Result.Error(folderResult.exception)
        }
        val folderId = (folderResult as Result.Success).data
        val fileName = "carry_forward_${req.year}.json"
        val uploadRes = googleDriveClient.uploadOrUpdateTextFile(folderId, fileName, statement.jsonContent)
        if (uploadRes is Result.Error) {
            return Result.Error(uploadRes.exception)
        }

        return Result.Success(Unit)
    }

    private val payableColumnLetters = setOf("W", "X", "Y", "Z", "AA", "AB", "AC")
    private val inflowColumnLetters = setOf("N", "O", "P", "Q", "R", "S", "T", "U")

    private fun isDebitColumn(column: String): Boolean {
        val c = column.uppercase()
        return c !in payableColumnLetters && c !in inflowColumnLetters
    }

    /** The id column of the table an edited column belongs to, and the id column of the older layout. */
    private fun uuidColumnsFor(column: String): Pair<String, String> {
        val c = column.uppercase()
        return when {
            c in payableColumnLetters ->
                SheetSchemaConstants.COL_LETTER_PAYABLE_UUID to SheetSchemaConstants.COL_LETTER_PAYABLE_UUID_V1
            c in inflowColumnLetters ->
                SheetSchemaConstants.COL_LETTER_INFLOW_UUID to SheetSchemaConstants.COL_LETTER_INFLOW_UUID_V1
            else ->
                SheetSchemaConstants.COL_LETTER_DEBIT_UUID to SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1
        }
    }

    /** Reads one id column of one tab (rows 4 down) into a map of lower case id to row number, once per apply. */
    private suspend fun loadUuidColumn(
        cache: MutableMap<Triple<String, String, String>, UuidColumn>,
        spreadsheetId: String,
        tab: String,
        colLetter: String
    ): Result<UuidColumn> {
        val key = Triple(spreadsheetId, tab, colLetter)
        cache[key]?.let { return Result.Success(it) }
        val res = googleSheetsClient.getRangeValues(spreadsheetId, "'$tab'!${colLetter}4:$colLetter")
        if (res is Result.Error) return Result.Error(res.exception)
        val map = HashMap<String, Int>()
        val cells = ArrayList<String>()
        (res as Result.Success).data.forEachIndexed { i, row ->
            val cell = row.firstOrNull()?.toString()?.trim()?.lowercase().orEmpty()
            cells.add(cell)
            if (cell.isNotEmpty()) map.putIfAbsent(cell, i + 4)
        }
        val column = UuidColumn(map, cells)
        cache[key] = column
        return Result.Success(column)
    }

    /** One id column read from row 4 down: id to row number, and the raw cell of every row read (index 0 is row 4). */
    private class UuidColumn(val rowById: Map<String, Int>, val cells: List<String>) {
        /** True when the row is inside the data read and its id cell is empty (an older row without an id). */
        fun isBlankAt(row: Int): Boolean = row >= 4 && row - 4 < cells.size && cells[row - 4].isEmpty()
    }

    private suspend fun applyAccountingPlan(
        spreadsheetId: String,
        plan: AccountingPlan,
        sourceSpreadsheetId: String? = null
    ): Result<Unit> {
        // Appends first
        for (append in plan.appends) {
            val (colUuid, colUuidV1) = when (append.table) {
                TableType.DEBIT -> SheetSchemaConstants.COL_LETTER_DEBIT_UUID to SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1
                TableType.INFLOW -> SheetSchemaConstants.COL_LETTER_INFLOW_UUID to SheetSchemaConstants.COL_LETTER_INFLOW_UUID_V1
                TableType.PAYABLE -> SheetSchemaConstants.COL_LETTER_PAYABLE_UUID to SheetSchemaConstants.COL_LETTER_PAYABLE_UUID_V1
            }
            val existingCheck = googleSheetsClient.findRowByUuid(spreadsheetId, append.tab, colUuid, append.uuid)
                .let { res ->
                    if (res is Result.Success && res.data == null) {
                        googleSheetsClient.findRowByUuid(spreadsheetId, append.tab, colUuidV1, append.uuid)
                    } else res
                }
            if (existingCheck is Result.Success && existingCheck.data != null) {
                continue
            } else if (existingCheck is Result.Error) {
                return Result.Error(existingCheck.exception)
            }

            val appendRes = when (append.table) {
                TableType.DEBIT -> googleSheetsClient.appendDebit(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
                TableType.INFLOW -> googleSheetsClient.appendInflow(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
                TableType.PAYABLE -> googleSheetsClient.appendPayable(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
            }

            if (appendRes is Result.Error) {
                return Result.Error(appendRes.exception)
            }
        }

        // Edits second (partitioned by spreadsheet if cross-workbook rollover)
        if (plan.edits.isNotEmpty()) {
            val editsByTarget = plan.edits.groupBy { edit ->
                if (sourceSpreadsheetId != null && sourceSpreadsheetId != spreadsheetId) {
                    sourceSpreadsheetId
                } else {
                    spreadsheetId
                }
            }

            // Every edit is applied to the row that currently holds its entry id, never to a remembered row number.
            // Each id column is read once per tab for the whole apply.
            val settledUuidByRow = mutableMapOf<Pair<String, Int>, String>()
            for (settled in plan.settled) {
                if (!settled.uuid.isNullOrBlank()) settledUuidByRow[Pair(settled.tab, settled.row)] = settled.uuid
            }
            val columnCache = mutableMapOf<Triple<String, String, String>, UuidColumn>()

            for ((targetId, edits) in editsByTarget) {
                val rangesWithValues = LinkedHashMap<String, List<List<Any?>>>()
                for (edit in edits) {
                    val expectedUuid = edit.uuid?.takeIf { it.isNotBlank() }
                        ?: settledUuidByRow[Pair(edit.tab, edit.row)]?.takeIf { isDebitColumn(edit.column) }
                    val actualRow = if (expectedUuid == null) {
                        // An older stored plan without ids: keep the planned row
                        edit.row
                    } else {
                        val (primaryCol, legacyCol) = uuidColumnsFor(edit.column)
                        val primary = loadUuidColumn(columnCache, targetId, edit.tab, primaryCol)
                        if (primary is Result.Error) return Result.Error(primary.exception)
                        val primaryColumn = (primary as Result.Success).data
                        var found = primaryColumn.rowById[expectedUuid.trim().lowercase()]
                        if (found == null) {
                            val legacy = loadUuidColumn(columnCache, targetId, edit.tab, legacyCol)
                            if (legacy is Result.Error) return Result.Error(legacy.exception)
                            found = (legacy as Result.Success).data.rowById[expectedUuid.trim().lowercase()]
                        }
                        // Both id columns were read in full: the row is really gone from this tab. Not retried.
                        // An older row written before ids existed has a blank id cell at the planned row: keep the planned row.
                        if (found == null && primaryColumn.isBlankAt(edit.row)) found = edit.row
                        found ?: return Result.Error(IllegalStateException("Sheet row moved or removed in ${edit.tab}; edit not applied"))
                    }
                    rangesWithValues["'${edit.tab}'!${edit.column}$actualRow"] = listOf(listOf(edit.value))
                }
                val editRes = googleSheetsClient.batchUpdateValues(targetId, rangesWithValues)
                if (editRes is Result.Error) return Result.Error(editRes.exception)
            }
        }

        // Apply plan edits and settled references locally to Room database
        applyPlanToLocalDatabase(plan)

        return Result.Success(Unit)
    }

    open suspend fun applyPlanToLocalDatabase(plan: AccountingPlan) {
        try {
            val tDao = resolveTransactionDao() ?: return
            val editsByTabAndRow = plan.edits.groupBy { Pair(it.tab, it.row) }
            val settledByTabAndRow = plan.settled.groupBy { Pair(it.tab, it.row) }
            val allTargetTabRows = (editsByTabAndRow.keys + settledByTabAndRow.keys).distinct()

            for ((tab, rowNum) in allTargetTabRows) {
                val settledRef = settledByTabAndRow[Pair(tab, rowNum)]?.firstOrNull()
                // The row's own id decides which local row this is. Tab and row number only serve a row without an
                // id: the tab is derived from the date there, which is wrong for a carried copy.
                val knownId = settledRef?.uuid?.takeIf { it.isNotBlank() }
                    ?: editsByTabAndRow[Pair(tab, rowNum)].orEmpty()
                        .firstOrNull { isDebitColumn(it.column) && !it.uuid.isNullOrBlank() }?.uuid
                val existingTxn = if (knownId != null) tDao.getById(knownId) else tDao.getByTabAndSheetRowIndex(tab, rowNum)

                if (existingTxn != null) {
                    var updated: com.financeautomation.data.local.entity.TransactionEntity = existingTxn
                    val rowEdits = editsByTabAndRow[Pair(tab, rowNum)].orEmpty()
                    for (edit in rowEdits) {
                        val col = edit.column.uppercase()
                        updated = when (col) {
                            "D" -> updated.copy(merchant = edit.value?.toString() ?: updated.merchant)
                            "G" -> {
                                val amt = (edit.value as? Number)?.toDouble()
                                    ?: edit.value?.toString()?.toDoubleOrNull()
                                    ?: updated.totalAmount
                                updated.copy(totalAmount = amt)
                            }
                            "H" -> updated.copy(companions = edit.value?.toString() ?: updated.companions)
                            "I" -> {
                                val share = (edit.value as? Number)?.toDouble()
                                    ?: edit.value?.toString()?.toDoubleOrNull()
                                    ?: updated.myShare
                                updated.copy(myShare = share)
                            }
                            "J" -> {
                                val owed = (edit.value as? Number)?.toDouble()
                                    ?: edit.value?.toString()?.toDoubleOrNull()
                                    ?: updated.owedToMe
                                updated.copy(owedToMe = owed)
                            }
                            "K" -> updated.copy(status = edit.value?.toString() ?: updated.status)
                            else -> updated
                        }
                    }
                    if (settledRef != null) {
                        val owed = settledRef.remaining
                        val st = if (owed <= 0.0) "Settled" else "Pending"
                        updated = updated.copy(owedToMe = owed, status = st)
                    }
                    tDao.update(updated)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.financeautomation.core.utils.Diagnostics.record(
                "AccountingSyncExecutor",
                "applyPlanToLocalDatabase failed: ${e.message}"
            )
        }
    }

    private fun mapDebitsFromSnapshot(snapshot: LedgerWorkbookSnapshot): List<LedgerDebit> {
        return snapshot.monthTabs.flatMap { month ->
            month.debits.map { it.toDomain(month.tabName) }
        }
    }

    private fun mapPayablesFromSnapshot(snapshot: LedgerWorkbookSnapshot): List<LedgerPayable> {
        return snapshot.monthTabs.flatMap { month ->
            month.payables.map { it.toDomain(month.tabName) }
        }
    }

    private fun LedgerDebitRow.toDomain(tab: String): LedgerDebit {
        return LedgerDebit(
            tab = tab,
            row = rowNumber,
            uuid = uuid,
            dateSerial = dateSerial,
            timeSerial = timeSerial,
            account = account,
            item = description,
            tripTag = tripTag,
            category = category,
            total = totalAmount,
            companions = companions,
            myShare = myShare,
            owed = owedToMe,
            status = status
        )
    }

    private fun LedgerPayableRow.toDomain(tab: String): LedgerPayable {
        return LedgerPayable(
            tab = tab,
            row = rowNumber,
            uuid = uuid,
            dateIncurredSerial = dateIncurredSerial,
            timeSerial = timeSerial,
            paidBy = paidBy,
            whatFor = whatFor,
            amountOwed = amountIOwe,
            dateRepaidSerial = dateRepaidSerial,
            status = repaymentStatus
        )
    }

    companion object {
        fun parseMonthKey(tabName: String): Int? {
            val match = MONTH_TAB_REGEX.find(tabName.trim()) ?: return null
            val mmm = match.groupValues[1].lowercase(Locale.US)
            val yy = match.groupValues[2].toIntOrNull() ?: return null
            val monthIdx = when (mmm) {
                "jan" -> 0
                "feb" -> 1
                "mar" -> 2
                "apr" -> 3
                "may" -> 4
                "jun" -> 5
                "jul" -> 6
                "aug" -> 7
                "sep" -> 8
                "oct" -> 9
                "nov" -> 10
                "dec" -> 11
                else -> return null
            }
            return (2000 + yy) * 12 + monthIdx
        }
    }

    fun findPrecedingTab(targetTab: String, availableTabs: List<String>): String? {
        val targetKey = parseMonthKey(targetTab)
        if (targetKey != null) {
            val precedingTabs = availableTabs
                .mapNotNull { tab -> parseMonthKey(tab)?.let { key -> tab to key } }
                .filter { (_, key) -> key < targetKey }
            return precedingTabs.maxByOrNull { (_, key) -> key }?.first
        }
        val targetIdx = availableTabs.indexOfFirst { it.equals(targetTab, ignoreCase = true) }
        return if (targetIdx > 0) {
            availableTabs[targetIdx - 1]
        } else {
            availableTabs.firstOrNull { !it.equals(targetTab, ignoreCase = true) }
        }
    }

    /**
     * Restores an income / credit row to Table 2 (Cols M-T) without running the settlement waterfall.
     * Preserves the stored hidden UUID in column AD. Idempotent: skips if row with UUID already exists.
     */
    suspend fun executeRestoreInflow(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<Unit> {
        val inDao = resolveInflowDao()
            ?: return Result.Error(IllegalStateException("InflowDao or provider must be supplied"))
        val entity = inDao.getById(item.transactionId)
            ?: return Result.Success(Unit)
        if (entity.status.equals("VOID", ignoreCase = true)) {
            return Result.Success(Unit)
        }

        val existingCheck = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_INFLOW_UUID, entity.id)
            .let { res ->
                if (res is Result.Success && res.data != null) res
                else googleSheetsClient.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_INFLOW_UUID_V1, entity.id)
            }
        if (existingCheck is Result.Success && existingCheck.data != null) {
            return Result.Success(Unit)
        } else if (existingCheck is Result.Error) {
            return Result.Error(existingCheck.exception)
        }

        val localDate = Instant.ofEpochMilli(entity.timestamp).atZone(DateSerialUtils.SHEET_ZONE).toLocalDate()
        val localTime = Instant.ofEpochMilli(entity.timestamp).atZone(DateSerialUtils.SHEET_ZONE).toLocalTime()
        val dateSerial = DateSerialUtils.toDateSerial(localDate)
        val timeSerial = DateSerialUtils.toTimeSerial(localTime)
        val cells = listOf(
            dateSerial,
            timeSerial,
            entity.account,
            entity.inflowType,
            entity.source,
            entity.note,
            entity.amount,
            ""
        )
        val appendRes = googleSheetsClient.appendInflow(spreadsheetId, tabName, cells, uuid = entity.id)
        return when (appendRes) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(appendRes.exception)
            is Result.Loading -> Result.Loading
        }
    }

    /**
     * Restores a payable row to Table 3 (Cols V-AB) without modifying other rows.
     * Preserves the stored hidden UUID in column AE. Idempotent: skips if row with UUID already exists.
     */
    suspend fun executeRestorePayable(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<Unit> {
        val pDao = resolvePayableDao()
            ?: return Result.Error(IllegalStateException("PayableDao or provider must be supplied"))
        val entity = pDao.getByUuid(item.transactionId)
            ?: return Result.Success(Unit)
        if (entity.repaymentStatus.equals("VOID", ignoreCase = true)) {
            return Result.Success(Unit)
        }

        val existingCheck = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID, entity.uuid)
            .let { res ->
                if (res is Result.Success && res.data != null) res
                else googleSheetsClient.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_PAYABLE_UUID_V1, entity.uuid)
            }
        if (existingCheck is Result.Success && existingCheck.data != null) {
            return Result.Success(Unit)
        } else if (existingCheck is Result.Error) {
            return Result.Error(existingCheck.exception)
        }

        val cells = listOf(
            entity.dateIncurredSerial ?: "",
            entity.timeSerial ?: "",
            entity.paidBy,
            entity.whatFor,
            entity.amountIOwe,
            entity.dateRepaidSerial ?: "",
            entity.repaymentStatus
        )
        val appendRes = googleSheetsClient.appendPayable(spreadsheetId, tabName, cells, uuid = entity.uuid)
        return when (appendRes) {
            is Result.Success -> Result.Success(Unit)
            is Result.Error -> Result.Error(appendRes.exception)
            is Result.Loading -> Result.Loading
        }
    }
}

/** Replaces the id of the Table 2 append in this plan with [inflowId] (ignored when blank). */
internal fun AccountingPlan.withInflowRowUuid(inflowId: String): AccountingPlan {
    if (inflowId.isBlank()) return this
    return copy(appends = appends.map { if (it.table == TableType.INFLOW) it.copy(uuid = inflowId) else it })
}
