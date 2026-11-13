package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.getByTabAndSheetRowIndex
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
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

/**
 * Handles two-phase durable execution of complex accounting operations in the sync queue:
 * Friend settlements (OP_LOG_CREDIT), payables (OP_LOG_PAYABLE), repayments (OP_LOG_REPAYMENT),
 * monthly rollover (OP_CARRY_FORWARD), and annual closing (OP_YEAR_CLOSE).
 */
class AccountingSyncExecutor(
    private val googleSheetsClient: GoogleSheetsClient,
    private val googleDriveClient: GoogleDriveClient,
    private val syncQueueDao: SyncQueueDao? = null,
    private val lineageDao: LineageDao? = null,
    private val transactionDao: TransactionDao? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val lineageDaoProvider: (suspend () -> LineageDao)? = null,
    private val transactionDaoProvider: (suspend () -> TransactionDao)? = null,
    private val accountsProvider: (suspend () -> List<String>)? = null,
    private val categoriesProvider: (suspend () -> List<String>)? = null,
    private val settlementWaterfallUseCase: SettlementWaterfallUseCase = SettlementWaterfallUseCase(),
    private val repaymentWaterfallUseCase: RepaymentWaterfallUseCase = RepaymentWaterfallUseCase(),
    private val carryForwardUseCase: CarryForwardUseCase = CarryForwardUseCase(),
    private val nettingUseCase: NettingUseCase = NettingUseCase(),
    private val yearCloseUseCase: YearCloseUseCase = YearCloseUseCase(),
    private val voidTransactionUseCase: VoidTransactionUseCase = VoidTransactionUseCase()
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
        syncQueueDaoProvider = null,
        lineageDaoProvider = null,
        transactionDaoProvider = null,
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
        val rowRes = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, "AD", req.inflowId)
        if (rowRes is Result.Error) return Result.Error(rowRes.exception)
        val row = (rowRes as Result.Success).data
            ?: return Result.Error(IllegalStateException("Income row not found in $tabName for ${req.inflowId}"))

        val reasonRes = googleSheetsClient.getCellString(spreadsheetId, tabName, "R$row")
        if (reasonRes is Result.Error) return Result.Error(reasonRes.exception)
        val currentReason = (reasonRes as Result.Success).data.orEmpty()

        val plan = voidTransactionUseCase.voidInflow(tabName, row, currentReason)
        val updates = plan.edits.associate { edit ->
            "'${edit.tab}'!${edit.column}${edit.row}" to listOf(listOf(edit.value))
        }
        return googleSheetsClient.batchUpdateValues(spreadsheetId, updates)
    }

    suspend fun executeLogPayable(
        item: SyncQueueEntity,
        spreadsheetId: String,
        tabName: String
    ): Result<Unit> {
        val plan: AccountingPlan
        if (item.planJson != null) {
            plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
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

            val newPayUuid = "pay_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val isFullyRepaid = nettingRes.netAmount <= 0.0
            val payCells = listOf(
                dateSerial,
                timeSerial,
                req.creditor,
                req.whatFor,
                AccountingUtils.round2(nettingRes.netAmount),
                if (isFullyRepaid) dateSerial else "",
                if (isFullyRepaid) "Repaid" else "Pending"
            )

            val appends = listOf(
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
        }

        return applyAccountingPlan(spreadsheetId, plan)
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
                resolveCarriedFn = resolveCarriedFn
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

        // 2. Read snapshot of workbook
        val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
        if (snapRes is Result.Error) return Result.Error(snapRes.exception)
        val snapshot = (snapRes as Result.Success).data

        val sourceTab = req.sourceTab ?: findPrecedingTab(targetTab, snapshot.monthTabs.map { it.tabName })
        if (sourceTab == null) {
            return Result.Success(Unit)
        }

        val targetMonth = snapshot.monthTabs.find { it.tabName.equals(targetTab, ignoreCase = true) }
        val sourceMonth = snapshot.monthTabs.find { it.tabName.equals(sourceTab, ignoreCase = true) }

        val targetDebits = targetMonth?.debits?.map { it.toDomain(targetTab) }.orEmpty()
        val targetPayables = targetMonth?.payables?.map { it.toDomain(targetTab) }.orEmpty()
        val sourceDebits = sourceMonth?.debits?.map { it.toDomain(sourceTab) }.orEmpty()
        val sourcePayables = sourceMonth?.payables?.map { it.toDomain(sourceTab) }.orEmpty()

        val rolloverResult = carryForwardUseCase(
            targetTab = targetTab,
            sourceTab = sourceTab,
            targetDebits = targetDebits,
            targetPayables = targetPayables,
            sourceDebits = sourceDebits,
            sourcePayables = sourcePayables
        )

        if (rolloverResult.skippedAlreadyCarried) {
            return Result.Success(Unit)
        }

        // Apply plan: appends first, then source status edits
        val applyResult = applyAccountingPlan(spreadsheetId, rolloverResult.plan)
        if (applyResult is Result.Success) {
            // Persist lineage records into Room in one transaction
            if (rolloverResult.lineageRecords.isNotEmpty()) {
                resolveLineageDao().insertAll(rolloverResult.lineageRecords.map { LineageEntity.fromDomain(it) })
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
        if (folderResult is Result.Success) {
            val folderId = folderResult.data
            val fileName = "carry_forward_${req.year}.json"
            googleDriveClient.uploadOrUpdateTextFile(folderId, fileName, statement.jsonContent)
        }

        return Result.Success(Unit)
    }

    private suspend fun applyAccountingPlan(
        spreadsheetId: String,
        plan: AccountingPlan
    ): Result<Unit> {
        // Appends first
        for (append in plan.appends) {
            val colUuid = when (append.table) {
                TableType.DEBIT -> "AC"
                TableType.INFLOW -> "AD"
                TableType.PAYABLE -> "AE"
            }
            val existingCheck = googleSheetsClient.findRowByUuid(spreadsheetId, append.tab, colUuid, append.uuid)
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

        // Edits second
        if (plan.edits.isNotEmpty()) {
            val rangesWithValues = plan.edits.associate { edit ->
                "'${edit.tab}'!${edit.column}${edit.row}" to listOf(listOf(edit.value))
            }
            val editRes = googleSheetsClient.batchUpdateValues(spreadsheetId, rangesWithValues)
            if (editRes is Result.Error) return Result.Error(editRes.exception)
        }

        // Apply plan edits and settled references locally to Room database
        applyPlanToLocalDatabase(plan)

        return Result.Success(Unit)
    }

    suspend fun applyPlanToLocalDatabase(plan: AccountingPlan) {
        try {
            val tDao = resolveTransactionDao() ?: return
            val editsByTabAndRow = plan.edits.groupBy { Pair(it.tab, it.row) }
            val settledByTabAndRow = plan.settled.groupBy { Pair(it.tab, it.row) }
            val allTargetTabRows = (editsByTabAndRow.keys + settledByTabAndRow.keys).distinct()

            for ((tab, rowNum) in allTargetTabRows) {
                val settledRef = settledByTabAndRow[Pair(tab, rowNum)]?.firstOrNull()
                val existingTxn = (settledRef?.uuid?.let { tDao.getById(it) })
                    ?: tDao.getByTabAndSheetRowIndex(tab, rowNum)

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

    fun parseMonthKey(tabName: String): Int? {
        val match = MONTH_TAB_REGEX.find(tabName.trim()) ?: return null
        val mmm = match.groupValues[1].lowercase(Locale.US)
        val yy = match.groupValues[2].toIntOrNull() ?: return null
        val monthIdx = when (mmm) {
            "jan" -> 1
            "feb" -> 2
            "mar" -> 3
            "apr" -> 4
            "may" -> 5
            "jun" -> 6
            "jul" -> 7
            "aug" -> 8
            "sep" -> 9
            "oct" -> 10
            "nov" -> 11
            "dec" -> 12
            else -> return null
        }
        return (2000 + yy) * 12 + monthIdx
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
}

/** Replaces the id of the Table 2 append in this plan with [inflowId] (ignored when blank). */
internal fun AccountingPlan.withInflowRowUuid(inflowId: String): AccountingPlan {
    if (inflowId.isBlank()) return this
    return copy(appends = appends.map { if (it.table == TableType.INFLOW) it.copy(uuid = inflowId) else it })
}
