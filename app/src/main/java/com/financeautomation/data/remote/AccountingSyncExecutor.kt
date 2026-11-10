package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.SyncQueueDao
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
import com.financeautomation.domain.usecase.YearCloseUseCase
import com.financeautomation.domain.utils.AccountingUtils
import java.util.UUID

/**
 * Handles two-phase durable execution of complex accounting operations in the sync queue:
 * Friend settlements (OP_LOG_CREDIT), payables (OP_LOG_PAYABLE), repayments (OP_LOG_REPAYMENT),
 * monthly rollover (OP_CARRY_FORWARD), and annual closing (OP_YEAR_CLOSE).
 */
class AccountingSyncExecutor(
    private val googleSheetsClient: GoogleSheetsClient,
    private val googleDriveClient: GoogleDriveClient,
    private val syncQueueDao: SyncQueueDao,
    private val lineageDao: LineageDao,
    private val settlementWaterfallUseCase: SettlementWaterfallUseCase = SettlementWaterfallUseCase(),
    private val repaymentWaterfallUseCase: RepaymentWaterfallUseCase = RepaymentWaterfallUseCase(),
    private val carryForwardUseCase: CarryForwardUseCase = CarryForwardUseCase(),
    private val nettingUseCase: NettingUseCase = NettingUseCase(),
    private val yearCloseUseCase: YearCloseUseCase = YearCloseUseCase()
) {

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
            val allDebits = if (snapRes is Result.Success) {
                mapDebitsFromSnapshot(snapRes.data)
            } else {
                emptyList()
            }

            val lineageRecords = lineageDao.getAll().map { it.toDomain() }
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
            )

            val planJson = AccountingPlanJsonAdapter.toJson(plan)
            syncQueueDao.updatePlanJson(item.id, planJson)
        }

        return applyAccountingPlan(spreadsheetId, plan)
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
            val allDebits = if (snapRes is Result.Success) {
                mapDebitsFromSnapshot(snapRes.data)
            } else {
                emptyList()
            }

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
            syncQueueDao.updatePlanJson(item.id, planJson)
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
            val allPayables = if (snapRes is Result.Success) {
                mapPayablesFromSnapshot(snapRes.data)
            } else {
                emptyList()
            }

            val lineageRecords = lineageDao.getAll().map { it.toDomain() }
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
            syncQueueDao.updatePlanJson(item.id, planJson)
        }

        return applyAccountingPlan(spreadsheetId, plan)
    }

    suspend fun executeCarryForward(
        item: SyncQueueEntity,
        spreadsheetId: String
    ): Result<Unit> {
        val req = AccountingRequestJsonAdapter.carryForwardRequestFromJson(item.payloadJson)
        val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
        if (snapRes is Result.Error) return Result.Error(snapRes.exception)
        val snapshot = (snapRes as Result.Success).data

        val targetTab = req.targetTab
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
                lineageDao.insertAll(rolloverResult.lineageRecords.map { LineageEntity.fromDomain(it) })
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
                TableType.DEBIT -> googleSheetsClient.appendDebit(spreadsheetId, append.tab, append.cells)
                TableType.INFLOW -> googleSheetsClient.appendInflow(spreadsheetId, append.tab, append.cells)
                TableType.PAYABLE -> googleSheetsClient.appendPayable(spreadsheetId, append.tab, append.cells)
            }

            if (appendRes is Result.Success) {
                val rowIdx = appendRes.data
                val uuidRes = googleSheetsClient.updateCell(spreadsheetId, append.tab, "$colUuid$rowIdx", append.uuid)
                if (uuidRes is Result.Error) return Result.Error(uuidRes.exception)
            } else {
                return Result.Error((appendRes as Result.Error).exception)
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

        return Result.Success(Unit)
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

    fun findPrecedingTab(targetTab: String, availableTabs: List<String>): String? {
        val targetIdx = availableTabs.indexOfFirst { it.equals(targetTab, ignoreCase = true) }
        return if (targetIdx > 0) {
            availableTabs[targetIdx - 1]
        } else {
            availableTabs.firstOrNull { !it.equals(targetTab, ignoreCase = true) }
        }
    }
}

