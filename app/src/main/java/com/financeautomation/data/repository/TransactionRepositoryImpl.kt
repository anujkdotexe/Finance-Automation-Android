package com.financeautomation.data.repository

import com.financeautomation.core.constants.SheetSchemaConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.isFuture
import com.financeautomation.core.utils.TimeGuardConstants
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.core.utils.SyncErrorKind
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.dao.getByTabAndSheetRowIndex
import com.financeautomation.data.local.FinanceDatabase
import androidx.room.withTransaction
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingPlanJsonAdapter
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveApiException
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.SheetsReadGate
import com.financeautomation.data.remote.isRetryable
import com.financeautomation.data.remote.isRunWideBackoff
import com.financeautomation.data.remote.SheetPayloadMapper
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.domain.model.AccountingPlan
import com.financeautomation.domain.model.CarryForwardRequest
import com.financeautomation.domain.model.CellEdit
import com.financeautomation.domain.model.LedgerDebit
import com.financeautomation.domain.model.LedgerPayable
import com.financeautomation.domain.model.LogCreditRequest
import com.financeautomation.domain.model.LogPayableRequest
import com.financeautomation.domain.model.LogRepaymentRequest
import com.financeautomation.domain.model.NewRow
import com.financeautomation.domain.model.ReconcileOutcome
import com.financeautomation.domain.model.TableType
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.YearCloseRequest
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.NettingUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import com.financeautomation.domain.usecase.VoidTransactionUseCase
import com.financeautomation.domain.utils.AccountingUtils
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Production implementation of [TransactionRepository] backed by Room SQLite database,
 * [SyncQueueDao] for offline resilience, and [GoogleSheetsClient] for cloud synchronization.
 */
class TransactionRepositoryImpl(
    private val transactionDao: TransactionDao? = null,
    private val syncQueueDao: SyncQueueDao? = null,
    private val transactionDaoProvider: (suspend () -> TransactionDao)? = null,
    private val syncQueueDaoProvider: (suspend () -> SyncQueueDao)? = null,
    private val payableDao: PayableDao? = null,
    private val payableDaoProvider: (suspend () -> PayableDao)? = null,
    private val lineageDao: LineageDao? = null,
    private val lineageDaoProvider: (suspend () -> LineageDao)? = null,
    private val inflowDao: InflowDao? = null,
    private val inflowDaoProvider: (suspend () -> InflowDao)? = null,
    private val databaseProvider: (suspend () -> FinanceDatabase)? = null,
    private val googleSheetsClient: GoogleSheetsClient? = null,
    private val preferencesRepository: PreferencesRepository? = null,
    private val workbookResolver: WorkbookResolver? = null,
    private val accountRepository: AccountRepository? = null,
    private val categoryRepository: CategoryRepository? = null,
    private val syncConfigUseCase: SyncConfigUseCase? = null,
    private val calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
    private val nettingUseCase: NettingUseCase = NettingUseCase(),
    private val voidTransactionUseCase: VoidTransactionUseCase = VoidTransactionUseCase(),
    val accountingSyncExecutor: AccountingSyncExecutor,
    var onTriggerSync: (() -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val readGate: SheetsReadGate = SheetsReadGate(),
    /** Drive modified time of a spreadsheet; lets an idle sync skip the full read when nothing changed. */
    private val modifiedTimeProvider: (suspend (String) -> Result<String?>)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val subscriptionsSheetSyncCoordinator: com.financeautomation.data.remote.SubscriptionsSheetSyncCoordinator? = null,
    private val tripsSheetSyncCoordinator: com.financeautomation.data.remote.TripsSheetSyncCoordinator? = null,
    private val subscriptionsSheetSyncCoordinatorProvider: (() -> com.financeautomation.data.remote.SubscriptionsSheetSyncCoordinator)? = null,
    private val tripsSheetSyncCoordinatorProvider: (() -> com.financeautomation.data.remote.TripsSheetSyncCoordinator)? = null,
    private val subscriptionCycleDao: com.financeautomation.data.local.dao.SubscriptionCycleDao? = null
) : TransactionRepository {

    constructor(
        transactionDao: TransactionDao,
        syncQueueDao: SyncQueueDao,
        payableDao: PayableDao? = null,
        lineageDao: LineageDao? = null,
        inflowDao: InflowDao? = null,
        googleSheetsClient: GoogleSheetsClient? = null,
        preferencesRepository: PreferencesRepository? = null,
        workbookResolver: WorkbookResolver? = null,
        accountRepository: AccountRepository? = null,
        categoryRepository: CategoryRepository? = null,
        syncConfigUseCase: SyncConfigUseCase? = null,
        calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
        nettingUseCase: NettingUseCase = NettingUseCase(),
        voidTransactionUseCase: VoidTransactionUseCase = VoidTransactionUseCase(),
        accountingSyncExecutor: AccountingSyncExecutor,
        onTriggerSync: (() -> Unit)? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        readGate: SheetsReadGate = SheetsReadGate(),
        modifiedTimeProvider: (suspend (String) -> Result<String?>)? = null,
        clock: () -> Long = System::currentTimeMillis,
        subscriptionsSheetSyncCoordinator: com.financeautomation.data.remote.SubscriptionsSheetSyncCoordinator? = null,
        tripsSheetSyncCoordinator: com.financeautomation.data.remote.TripsSheetSyncCoordinator? = null,
        subscriptionCycleDao: com.financeautomation.data.local.dao.SubscriptionCycleDao? = null
    ) : this(
        transactionDao = transactionDao,
        syncQueueDao = syncQueueDao,
        transactionDaoProvider = null,
        syncQueueDaoProvider = null,
        payableDao = payableDao,
        payableDaoProvider = null,
        lineageDao = lineageDao,
        lineageDaoProvider = null,
        inflowDao = inflowDao,
        inflowDaoProvider = null,
        databaseProvider = null,
        googleSheetsClient = googleSheetsClient,
        preferencesRepository = preferencesRepository,
        workbookResolver = workbookResolver,
        accountRepository = accountRepository,
        categoryRepository = categoryRepository,
        syncConfigUseCase = syncConfigUseCase,
        calculateSplitUseCase = calculateSplitUseCase,
        nettingUseCase = nettingUseCase,
        voidTransactionUseCase = voidTransactionUseCase,
        accountingSyncExecutor = accountingSyncExecutor,
        onTriggerSync = onTriggerSync,
        ioDispatcher = ioDispatcher,
        readGate = readGate,
        modifiedTimeProvider = modifiedTimeProvider,
        clock = clock,
        subscriptionsSheetSyncCoordinator = subscriptionsSheetSyncCoordinator,
        tripsSheetSyncCoordinator = tripsSheetSyncCoordinator,
        subscriptionsSheetSyncCoordinatorProvider = null,
        tripsSheetSyncCoordinatorProvider = null,
        subscriptionCycleDao = subscriptionCycleDao
    )

    private fun resolveSubscriptionsSheetSyncCoordinator(): com.financeautomation.data.remote.SubscriptionsSheetSyncCoordinator? =
        subscriptionsSheetSyncCoordinator ?: subscriptionsSheetSyncCoordinatorProvider?.invoke()

    private fun resolveTripsSheetSyncCoordinator(): com.financeautomation.data.remote.TripsSheetSyncCoordinator? =
        tripsSheetSyncCoordinator ?: tripsSheetSyncCoordinatorProvider?.invoke()

    internal val syncMutex = Mutex()

    private class SyncLockHolder : kotlin.coroutines.AbstractCoroutineContextElement(SyncLockHolder) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<SyncLockHolder>
    }

    private suspend fun <T> withSyncLock(block: suspend () -> T): T {
        return if (kotlin.coroutines.coroutineContext[SyncLockHolder] != null) {
            block()
        } else {
            syncMutex.withLock {
                kotlinx.coroutines.withContext(SyncLockHolder()) {
                    block()
                }
            }
        }
    }

    private suspend fun findDebitRow(
        spreadsheetId: String,
        tabName: String,
        uuid: String,
        checkV1Fallback: Boolean = true
    ): Result<Int?> {
        val client = googleSheetsClient ?: return Result.Error(IllegalStateException("GoogleSheetsClient unavailable"))
        val res = client.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_DEBIT_UUID, uuid)
        if (res is Result.Success && res.data != null) return res
        if (res is Result.Error || !checkV1Fallback) return res
        // Fallback for legacy 31-column V1 tabs
        return client.findRowByUuid(spreadsheetId, tabName, SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1, uuid)
    }

    /**
     * Asks for a background sync after something was queued. A failure to schedule must never fail the save,
     * because the periodic job still picks the queue up.
     */
    private fun requestSync() {
        try {
            onTriggerSync?.invoke()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.financeautomation.core.utils.Diagnostics.record("TransactionRepositoryImpl", "sync request failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend inline fun <T> runInDbTransaction(crossinline block: suspend () -> T): T {
        val db = databaseProvider?.invoke()
        return if (db != null) {
            db.withTransaction { block() }
        } else {
            block()
        }
    }

    override suspend fun requeueYearForNewWorkbook(year: Int) = withContext(ioDispatcher) {
        withSyncLock {
            Diagnostics.record("WorkbookRecovery", "WORKBOOK_REPLACED")

            val zone = ZoneId.of("Asia/Kolkata")
            val startMillis = ZonedDateTime.of(year, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
            val endMillis = ZonedDateTime.of(year + 1, 1, 1, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
            val startSerial = DateSerialUtils.toDateSerial(java.time.LocalDate.of(year, 1, 1))
            val endSerial = DateSerialUtils.toDateSerial(java.time.LocalDate.of(year + 1, 1, 1))

            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val inDao = resolveInflowDao()
            val pDao = resolvePayableDao()

            val txns = tDao.getAllForYear(startMillis, endMillis)
            val inflows = inDao?.getAllForYear(startMillis, endMillis).orEmpty()
            val payables = pDao?.getAllForYear(startSerial, endSerial, startMillis, endMillis).orEmpty()
            val activeIds = qDao.getActiveTransactionIds().toSet()

            val voidYearIds = activeIds.filter { id ->
                val entity = tDao.getById(id)
                entity != null && entity.timestamp >= startMillis && entity.timestamp < endMillis && entity.status.equals("Void", ignoreCase = true)
            }
            val allTxnIdsToDelete = (txns.map { it.id } + voidYearIds).distinct()

            runInDbTransaction {
                if (allTxnIdsToDelete.isNotEmpty()) {
                    allTxnIdsToDelete.chunked(SQLITE_IN_CHUNK).forEach { chunk ->
                        qDao.deletePendingForTransactions(chunk)
                    }
                }

                val currentPending = qDao.getAllPending()

                val pendingCreditIds = currentPending.filter {
                    it.operationType == SyncQueueEntity.OP_LOG_CREDIT
                }.map { it.transactionId }.toSet()
                val inflowsToRestore = inflows.filterNot { it.id in pendingCreditIds }

                val remainingPending = qDao.getAllPending()
                val minPendingCreatedAt = remainingPending.minOfOrNull { it.createdAt }
                val totalRestoreCount = txns.size + inflowsToRestore.size + payables.size
                val baseTimestamp = if (minPendingCreatedAt != null) {
                    minPendingCreatedAt - 1000L - (totalRestoreCount * 10L)
                } else {
                    System.currentTimeMillis()
                }
                var restoreIndex = 0L

                for (txn in txns) {
                    tDao.resetSyncStateForRequeue(txn.id)
                    qDao.insert(
                        SyncQueueEntity(
                            transactionId = txn.id,
                            operationType = SyncQueueEntity.OP_INSERT,
                            payloadJson = txn.id,
                            status = SyncQueueEntity.STATUS_PENDING,
                            createdAt = baseTimestamp + (restoreIndex++) * 10L
                        )
                    )
                }
                for (inflow in inflowsToRestore) {
                    qDao.insert(
                        SyncQueueEntity(
                            transactionId = inflow.id,
                            operationType = SyncQueueEntity.OP_RESTORE_INFLOW,
                            payloadJson = AccountingRequestJsonAdapter.restoreInflowRequestToJson(
                                com.financeautomation.domain.model.RestoreInflowRequest(inflow.id, inflow.timestamp)
                            ),
                            status = SyncQueueEntity.STATUS_PENDING,
                            createdAt = baseTimestamp + (restoreIndex++) * 10L
                        )
                    )
                }
                for (payable in payables) {
                    qDao.insert(
                        SyncQueueEntity(
                            transactionId = payable.uuid,
                            operationType = SyncQueueEntity.OP_RESTORE_PAYABLE,
                            payloadJson = AccountingRequestJsonAdapter.restorePayableRequestToJson(
                                com.financeautomation.domain.model.RestorePayableRequest(payable.uuid, payable.tabName, year)
                            ),
                            status = SyncQueueEntity.STATUS_PENDING,
                            createdAt = baseTimestamp + (restoreIndex++) * 10L
                        )
                    )
                }
            }
        }
        requestSync()
    }

    private suspend fun resolveTransactionDao(): TransactionDao =
        transactionDao ?: transactionDaoProvider?.invoke()
            ?: throw IllegalStateException("TransactionDao or provider must be supplied")

    private suspend fun resolveSyncQueueDao(): SyncQueueDao =
        syncQueueDao ?: syncQueueDaoProvider?.invoke()
            ?: throw IllegalStateException("SyncQueueDao or provider must be supplied")

    private suspend fun resolvePayableDao(): PayableDao? =
        payableDao ?: payableDaoProvider?.invoke()

    private suspend fun resolveLineageDao(): LineageDao? =
        lineageDao ?: lineageDaoProvider?.invoke() ?: databaseProvider?.invoke()?.lineageDao()

    private suspend fun resolveInflowDao(): InflowDao? =
        inflowDao ?: inflowDaoProvider?.invoke() ?: databaseProvider?.invoke()?.inflowDao()

    override fun getTransactions(): Flow<List<Transaction>> = flow {
        val dao = resolveTransactionDao()
        emitAll(dao.getTransactionsFlow().map { list -> list.map { it.toDomain() } })
    }.flowOn(ioDispatcher)

    override fun getNeedsReview(): Flow<List<Transaction>> = flow {
        val dao = resolveTransactionDao()
        emitAll(dao.getNeedsReviewFlow().map { list -> list.map { it.toDomain() } })
    }.flowOn(ioDispatcher)

    override suspend fun getTransactionById(id: String): Transaction? = withContext(ioDispatcher) {
        resolveTransactionDao().getById(id)?.toDomain()
    }

    override suspend fun insertTransaction(transaction: Transaction): Result<Unit> =
        insertTransactionWith(transaction) {}

    override suspend fun insertTransactionWith(
        transaction: Transaction,
        inSameTransaction: suspend () -> Unit
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            if (isFuture(transaction.timestamp, nowMillis = clock())) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val entity = TransactionEntity.fromDomain(transaction)
            runInDbTransaction {
                tDao.insert(entity)
                inSameTransaction()

                if (transaction.syncState == SyncState.PENDING) {
                    val queueItem = SyncQueueEntity(
                        transactionId = transaction.id,
                        operationType = SyncQueueEntity.OP_INSERT,
                        payloadJson = transaction.id
                    )
                    qDao.insert(queueItem)
                }
            }
            if (transaction.syncState == SyncState.PENDING) requestSync()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = withContext(ioDispatcher) {
        // Wait for a running sync: it may be delivering this very row, and the edit must be queued after it
        withSyncLock {
        try {
            if (isFuture(transaction.timestamp, nowMillis = clock())) return@withSyncLock Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val entity = TransactionEntity.fromDomain(transaction)
            runInDbTransaction {
                tDao.update(entity)

                // The insert reads the row when it runs, so a change made before it ran needs no second item
                val insertWaiting = qDao.getAllQueueFlow().first().any {
                    it.transactionId == transaction.id && it.operationType == SyncQueueEntity.OP_INSERT && it.planJson == null
                }
                if (!insertWaiting) {
                    qDao.insert(
                        SyncQueueEntity(
                            transactionId = transaction.id,
                            operationType = SyncQueueEntity.OP_UPDATE,
                            payloadJson = transaction.id
                        )
                    )
                }
            }
            requestSync()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
        }
    }

    override suspend fun logCredit(
        settler: String,
        amount: Double,
        explicitRefs: List<String>,
        account: String,
        inflowType: String,
        reason: String,
        timestamp: Long
    ): Result<String> = withContext(ioDispatcher) {
        try {
            if (!amount.isFinite() || amount <= 0.0) return@withContext Result.Error(IllegalArgumentException(AMOUNT_MESSAGE))
            if (isFuture(timestamp, nowMillis = clock())) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            val reqId = "cred_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val req = LogCreditRequest(
                requestId = reqId,
                settler = settler,
                amount = amount,
                explicitRefs = explicitRefs,
                account = account,
                inflowType = inflowType,
                reason = reason,
                timestamp = timestamp
            )
            val queueItem = SyncQueueEntity(
                transactionId = reqId,
                operationType = SyncQueueEntity.OP_LOG_CREDIT,
                payloadJson = AccountingRequestJsonAdapter.creditRequestToJson(req)
            )
            val inflow = InflowEntity(
                id = reqId,
                timestamp = timestamp,
                amount = amount,
                account = account,
                inflowType = inflowType,
                source = settler,
                note = reason,
                createdAt = System.currentTimeMillis(),
                status = "CONFIRMED"
            )
            runInDbTransaction {
                resolveInflowDao()?.insert(inflow)
                resolveSyncQueueDao().insert(queueItem)
            }
            requestSync()
            Result.Success(reqId)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun logPayable(
        creditor: String,
        amount: Double,
        whatFor: String,
        timestamp: Long,
        category: String
    ): Result<String> = withContext(ioDispatcher) {
        try {
            if (!amount.isFinite() || amount <= 0.0) return@withContext Result.Error(IllegalArgumentException(AMOUNT_MESSAGE))
            if (isFuture(timestamp, nowMillis = clock())) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            val reqId = "pay_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val txnId = "txn_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val effectiveCategory = category.trim().ifBlank { com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER }
            val dateSerial = DateSerialUtils.toDateSerial(timestamp)
            val timeSerial = DateSerialUtils.toTimeSerial(timestamp)
            val desc = whatFor.trim().ifBlank { "Paid by $creditor" }

            val txnEntity = TransactionEntity(
                id = txnId,
                timestamp = timestamp,
                dateSerial = dateSerial,
                timeSerial = timeSerial,
                account = "Other",
                merchant = desc,
                tripTag = "",
                category = effectiveCategory,
                totalAmount = amount,
                companions = "Personal",
                myShare = amount,
                owedToMe = 0.0,
                status = TransactionStatus.PERSONAL.value,
                sourcePackage = "",
                reviewState = ReviewState.CONFIRMED.name,
                syncState = SyncState.PENDING.name,
                sheetRowIndex = null
            )
            resolveTransactionDao().insert(txnEntity)

            val pDao = resolvePayableDao()
            val effectiveWhatFor = com.financeautomation.core.utils.PayableFormatUtils.buildEffectiveWhatFor(whatFor, effectiveCategory)
            pDao?.upsert(
                PayableEntity(
                    uuid = reqId,
                    dateIncurredSerial = dateSerial,
                    timeSerial = timeSerial,
                    paidBy = creditor,
                    whatFor = effectiveWhatFor,
                    amountIOwe = amount,
                    dateRepaidSerial = null,
                    repaymentStatus = "Pending",
                    tabName = "",
                    sheetRowIndex = null
                )
            )

            val req = LogPayableRequest(
                requestId = reqId,
                creditor = creditor,
                amount = amount,
                whatFor = whatFor,
                timestamp = timestamp,
                category = effectiveCategory,
                transactionId = txnId
            )
            val queueItem = SyncQueueEntity(
                transactionId = reqId,
                operationType = SyncQueueEntity.OP_LOG_PAYABLE,
                payloadJson = AccountingRequestJsonAdapter.payableRequestToJson(req)
            )
            resolveSyncQueueDao().insert(queueItem)
            requestSync()
            Result.Success(reqId)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun logFriendPaid(
        creditor: String,
        amount: Double,
        whatFor: String,
        timestamp: Long,
        category: String
    ): Result<String> = logPayable(creditor, amount, whatFor, timestamp, category)

    override suspend fun logRepayment(
        creditor: String,
        amount: Double,
        explicitRef: String?,
        timestamp: Long
    ): Result<String> = logRepayment(creditor, amount, explicitRef, timestamp, null, null)

    override suspend fun logRepayment(
        creditor: String,
        amount: Double,
        explicitRef: String?,
        timestamp: Long,
        account: String?,
        note: String?
    ): Result<String> = withContext(ioDispatcher) {
        try {
            if (!amount.isFinite() || amount <= 0.0) return@withContext Result.Error(IllegalArgumentException(AMOUNT_MESSAGE))
            if (isFuture(timestamp, nowMillis = clock())) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)

            val pDao = resolvePayableDao()
            val allPayables = pDao?.getAll().orEmpty()
            val openPayables = allPayables.filter {
                (it.repaymentStatus.equals("Pending", ignoreCase = true) || it.repaymentStatus.equals("Partial", ignoreCase = true)) &&
                    com.financeautomation.domain.utils.AccountingUtils.tokens(it.paidBy).any { token ->
                        com.financeautomation.domain.utils.AccountingUtils.eq(token, creditor)
                    } &&
                    it.amountIOwe > 0.0
            }

            if (openPayables.isEmpty()) {
                return@withContext Result.Error(
                    IllegalStateException("Nothing is owed to $creditor")
                )
            }

            var remainingRepayment = amount
            val dateSerial = DateSerialUtils.toDateSerial(timestamp)
            for (openPayable in openPayables) {
                if (remainingRepayment <= com.financeautomation.domain.utils.Tolerances.MONEY_LEFT) break
                val repRes = nettingUseCase.applyRepaymentToPayable(
                    com.financeautomation.domain.model.LedgerPayable(
                        tab = openPayable.tabName,
                        row = openPayable.sheetRowIndex ?: 0,
                        uuid = openPayable.uuid,
                        dateIncurredSerial = openPayable.dateIncurredSerial,
                        timeSerial = openPayable.timeSerial,
                        paidBy = openPayable.paidBy,
                        whatFor = openPayable.whatFor,
                        amountOwed = openPayable.amountIOwe,
                        dateRepaidSerial = openPayable.dateRepaidSerial,
                        status = openPayable.repaymentStatus
                    ),
                    creditor,
                    remainingRepayment,
                    dateSerial
                ) ?: continue

                remainingRepayment = com.financeautomation.domain.utils.AccountingUtils.round2(remainingRepayment - repRes.applied)
                val updatedPayable = openPayable.copy(
                    amountIOwe = repRes.newOwed,
                    repaymentStatus = repRes.newStatus,
                    paidBy = repRes.newPaidBy,
                    dateRepaidSerial = dateSerial
                )
                pDao?.upsert(updatedPayable)
            }

            val reqId = "rep_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val req = LogRepaymentRequest(
                requestId = reqId,
                creditor = creditor,
                amount = amount,
                explicitRef = explicitRef,
                timestamp = timestamp,
                account = account,
                note = note
            )
            val queueItem = SyncQueueEntity(
                transactionId = reqId,
                operationType = SyncQueueEntity.OP_LOG_REPAYMENT,
                payloadJson = AccountingRequestJsonAdapter.repaymentRequestToJson(req)
            )
            resolveSyncQueueDao().insert(queueItem)
            requestSync()
            Result.Success(reqId)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun carryForward(
        targetTab: String,
        sourceTab: String?
    ): Result<Unit> = withContext(ioDispatcher) {
        try {
            val qDao = resolveSyncQueueDao()
            val alreadyWaiting = qDao.getAllQueueFlow().first()
                .filter { it.operationType == SyncQueueEntity.OP_CARRY_FORWARD }
                .map { AccountingRequestJsonAdapter.carryForwardRequestFromJson(it.payloadJson) }
                .any { it.targetTab == targetTab && it.sourceTab == sourceTab }
            if (alreadyWaiting) {
                return@withContext Result.Success(Unit)
            }

            val reqId = "cf_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val accs = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val cats = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
            val req = CarryForwardRequest(
                targetTab = targetTab,
                sourceTab = sourceTab,
                accounts = accs,
                categories = cats
            )
            val queueItem = SyncQueueEntity(
                transactionId = reqId,
                operationType = SyncQueueEntity.OP_CARRY_FORWARD,
                payloadJson = AccountingRequestJsonAdapter.carryForwardRequestToJson(req)
            )
            resolveSyncQueueDao().insert(queueItem)
            requestSync()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun closeYear(year: Int): Result<Unit> = withContext(ioDispatcher) {
        try {
            val reqId = "yc_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val req = YearCloseRequest(year = year)
            val queueItem = SyncQueueEntity(
                transactionId = reqId,
                operationType = SyncQueueEntity.OP_YEAR_CLOSE,
                payloadJson = AccountingRequestJsonAdapter.yearCloseRequestToJson(req)
            )
            resolveSyncQueueDao().insert(queueItem)
            requestSync()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun voidTransaction(id: String): Result<Unit> = voidTransaction(id, false)

    override suspend fun voidTransaction(id: String, deleteSheetRow: Boolean): Result<Unit> = withContext(ioDispatcher) {
        // A running sync may be delivering this very row; wait for it so the decision below sees the final queue
        withSyncLock { voidTransactionLocked(id, deleteSheetRow) }
    }

    private suspend fun voidTransactionLocked(id: String, deleteSheetRow: Boolean = false): Result<Unit> {
        try {
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val existing = tDao.getById(id)
            if (existing == null) {
                val iDao = resolveInflowDao()
                val existingInflow = iDao?.getById(id)
                if (existingInflow != null) {
                    return voidInflowLocked(id, iDao, deleteSheetRow)
                }
                return Result.Error(IllegalArgumentException("Transaction not found: $id"))
            }

            val voidTitle = if (existing.merchant.startsWith("[Void] ")) {
                existing.merchant
            } else {
                "[Void] ${existing.merchant}"
            }

            // Never delivered: there is nothing on the sheet to void, so drop its queue items and void it here only
            // A stored plan means the delivery may have started and the sheet may already hold the row: queue a void then
            val neverDelivered = existing.sheetRowIndex == null &&
                qDao.getAllQueueFlow().first().any {
                    it.transactionId == id && it.operationType == SyncQueueEntity.OP_INSERT && it.planJson == null
                }
            if (neverDelivered) {
                runInDbTransaction {
                    qDao.deleteByTransactionId(id)
                    if (deleteSheetRow) {
                        tDao.deleteById(id)
                    } else {
                        tDao.voidTransaction(id, voidTitle)
                    }
                }
                requestSync()
                return Result.Success(Unit)
            }

            runInDbTransaction {
                tDao.voidTransaction(id, voidTitle)

                val req = com.financeautomation.domain.model.VoidExpenseRequest(id = id, deleteSheetRow = deleteSheetRow)
                val queueItem = SyncQueueEntity(
                    transactionId = id,
                    operationType = SyncQueueEntity.OP_VOID,
                    payloadJson = AccountingRequestJsonAdapter.voidExpenseRequestToJson(req)
                )
                qDao.insert(queueItem)
            }
            requestSync()
            return Result.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.Error(e)
        }
    }

    /**
     * Voids an income entry. Caller holds the sync lock.
     * - Credit still in the queue: it never reached the sheet, so it is simply dropped.
     * - Credit already delivered: a void is queued so the sheet row is corrected too.
     */
    private suspend fun voidInflowLocked(id: String, iDao: InflowDao, deleteSheetRow: Boolean = false): Result<Unit> {
        val qDao = resolveSyncQueueDao()
        val inflow = iDao.getById(id) ?: return Result.Error(IllegalArgumentException("Transaction not found: $id"))
        val queued = qDao.getAllQueueFlow().first().filter { it.transactionId == id }
        if (queued.any { it.operationType == SyncQueueEntity.OP_LOG_CREDIT }) {
            runInDbTransaction {
                qDao.deleteByTransactionId(id)
                if (deleteSheetRow) {
                    iDao.deleteById(id)
                } else {
                    iDao.updateStatus(id, "VOID")
                }
            }
            return Result.Success(Unit)
        }
        if (queued.any { it.operationType == SyncQueueEntity.OP_VOID_INFLOW }) {
            if (deleteSheetRow) {
                iDao.deleteById(id)
            } else {
                iDao.updateStatus(id, "VOID")
            }
            return Result.Success(Unit)
        }
        if (preferencesRepository?.getPreference(SettledInflowMarker.key(id)) == "1") {
            return Result.Error(IllegalStateException(SettledInflowMarker.BLOCKED_MESSAGE))
        }
        val request = com.financeautomation.domain.model.VoidInflowRequest(
            inflowId = id,
            timestamp = inflow.timestamp,
            deleteSheetRow = deleteSheetRow
        )
        runInDbTransaction {
            iDao.updateStatus(id, "VOID")
            qDao.insert(
                SyncQueueEntity(
                    transactionId = id,
                    operationType = SyncQueueEntity.OP_VOID_INFLOW,
                    payloadJson = AccountingRequestJsonAdapter.voidInflowRequestToJson(request)
                )
            )
        }
        requestSync()
        return Result.Success(Unit)
    }

    override suspend fun undoRecentSave(id: String): Result<Unit> = withContext(ioDispatcher) {
        withSyncLock {
            try {
                val tDao = resolveTransactionDao()
                val qDao = resolveSyncQueueDao()
                val existing = tDao.getById(id)
                if (existing == null) {
                    val iDao = resolveInflowDao()
                    if (iDao?.getById(id) != null) {
                        return@withSyncLock voidInflowLocked(id, iDao)
                    }
                    // Payables and repayments have no local row: they exist only as a queue item until delivered
                    val queued = qDao.getAllQueueFlow().first().filter { it.transactionId == id }
                    val isLoggedEntry = id.startsWith("pay_") || id.startsWith("rep_")
                    if (isLoggedEntry && queued.isNotEmpty()) {
                        if (id.startsWith("pay_")) {
                            val qItem = queued.first()
                            try {
                                val payReq = AccountingRequestJsonAdapter.payableRequestFromJson(qItem.payloadJson)
                                payReq.transactionId?.let { tid ->
                                    tDao.voidTransaction(tid, "[Void] Friend paid")
                                }
                                resolvePayableDao()?.deleteByUuid(id)
                            } catch (_: Exception) {}
                        }
                        qDao.deleteByTransactionId(id)
                        return@withSyncLock Result.Success(Unit)
                    }
                    if (isLoggedEntry) {
                        return@withSyncLock Result.Error(
                            IllegalStateException("This entry is already recorded on the sheet, so it can no longer be undone here")
                        )
                    }
                    return@withSyncLock Result.Error(IllegalArgumentException("Transaction not found: $id"))
                }

                val pendingInsert = qDao.getAllPending().any {
                    it.transactionId == id && it.operationType == SyncQueueEntity.OP_INSERT && it.planJson == null
                }
                if (pendingInsert && existing.sheetRowIndex == null) {
                    val voidTitle = if (existing.merchant.startsWith("[Void] ")) {
                        existing.merchant
                    } else {
                        "[Void] ${existing.merchant}"
                    }
                    runInDbTransaction {
                        qDao.deleteByTransactionId(id)
                        tDao.voidTransaction(id, voidTitle)
                    }
                    Result.Success(Unit)
                } else {
                    voidTransactionLocked(id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Error(e)
            }
        }
    }

    private class VoidedScan(val transactionIds: List<String>, val inflowIds: List<String>, val skipped: Int)

    /** Voided rows split into those safe to delete and those whose void is still queued for the sheet. */
    private suspend fun scanVoided(): VoidedScan {
        val queue = resolveSyncQueueDao().getAllQueueFlow().first()
        // Any queue item, pending or failed, means the sheet may still show the row as live: keep it
        val waiting = queue.map { it.transactionId }.toSet()
        val voidedTxns = resolveTransactionDao().getTransactionsFlow().first()
            .filter { it.status.equals(TransactionStatus.VOID.value, ignoreCase = true) }
        val voidedInflows = resolveInflowDao()?.getAllInflows()?.first().orEmpty()
            .filter { it.status.equals("VOID", ignoreCase = true) }
        return VoidedScan(
            transactionIds = voidedTxns.map { it.id }.filter { it !in waiting },
            inflowIds = voidedInflows.map { it.id }.filter { it !in waiting },
            skipped = voidedTxns.count { it.id in waiting } + voidedInflows.count { it.id in waiting }
        )
    }

    override suspend fun previewVoidedHistory(): com.financeautomation.domain.model.VoidedHistory = withContext(ioDispatcher) {
        val scan = scanVoided()
        com.financeautomation.domain.model.VoidedHistory(scan.transactionIds.size, scan.inflowIds.size, scan.skipped)
    }

    override suspend fun clearVoidedHistory(): Result<com.financeautomation.domain.model.VoidedHistory> = withContext(ioDispatcher) {
        withSyncLock {
            try {
                val scan = scanVoided()
                val tDao = resolveTransactionDao()
                val qDao = resolveSyncQueueDao()
                val iDao = resolveInflowDao()
                runInDbTransaction {
                    // Conditional: a row whose void was undone in the meantime is no longer void and is kept
                    scan.transactionIds.forEach { id ->
                        if (tDao.deleteIfVoid(id)) qDao.deleteByTransactionId(id)
                    }
                    scan.inflowIds.forEach { id ->
                        if (iDao?.deleteIfVoid(id) == true) qDao.deleteByTransactionId(id)
                    }
                }
                Result.Success(com.financeautomation.domain.model.VoidedHistory(scan.transactionIds.size, scan.inflowIds.size, scan.skipped))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Error(e)
            }
        }
    }

    override suspend fun discardQueueItem(queueItemId: Long): Result<Unit> = withContext(ioDispatcher) {
        withSyncLock {
            try {
                val qDao = resolveSyncQueueDao()
                val item = qDao.getById(queueItemId) ?: return@withSyncLock Result.Success(Unit)
                val copies = qDao.getAllQueueFlow().first()
                    .filter { it.transactionId == item.transactionId && it.operationType == item.operationType }
                runInDbTransaction {
                    copies.forEach { qDao.deleteById(it.id) }
                    qDao.deleteById(queueItemId)
                }
                // Requests such as a credit have no local transaction row; only rows that exist need their state set
                if (resolveTransactionDao().getById(item.transactionId) != null || resolveInflowDao()?.getById(item.transactionId) != null) {
                    discardLocalOnly(item.transactionId, voidIt = item.operationType == SyncQueueEntity.OP_INSERT)
                }
                Result.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Error(e)
            }
        }
    }

    override suspend fun discardLocalOnly(id: String, voidIt: Boolean): Result<Unit> = withContext(ioDispatcher) {
        try {
            val tDao = resolveTransactionDao()
            val existing = tDao.getById(id)
            if (existing == null) {
                val iDao = resolveInflowDao()
                val existingInflow = iDao?.getById(id)
                if (existingInflow != null) {
                    val qDao = resolveSyncQueueDao()
                    runInDbTransaction {
                        if (voidIt) {
                            iDao.updateStatus(id, "VOID")
                        }
                        qDao.deleteByTransactionId(id)
                    }
                    return@withContext Result.Success(Unit)
                }
                return@withContext Result.Error(IllegalArgumentException("Transaction not found: $id"))
            }
            val updated = if (voidIt) {
                val voidTitle = if (existing.merchant.startsWith("[Void] ")) {
                    existing.merchant
                } else {
                    "[Void] ${existing.merchant}"
                }
                existing.copy(
                    status = TransactionStatus.VOID.value,
                    merchant = voidTitle,
                    reviewState = ReviewState.CONFIRMED.name,
                    syncState = SyncState.VOID.name
                )
            } else {
                existing.copy(
                    reviewState = ReviewState.CONFIRMED.name,
                    syncState = if (existing.status.equals(TransactionStatus.VOID.value, ignoreCase = true)) SyncState.VOID.name else SyncState.SYNCED.name
                )
            }
            tDao.update(updated)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun findRecentSimilar(
        amount: Double,
        windowMillis: Long
    ): List<Transaction> = withContext(ioDispatcher) {
        val now = System.currentTimeMillis()
        val minTime = now - windowMillis
        val maxTime = now + 5000L
        val epsilon = 0.01
        val minAmount = amount - epsilon
        val maxAmount = amount + epsilon
        resolveTransactionDao().getRecentSimilar(minTime, maxTime, minAmount, maxAmount).map { it.toDomain() }
    }

    private val sheetTimeZone: ZoneId = ZoneId.of("Asia/Kolkata")

    private fun isTransportOrServerError(throwable: Throwable): Boolean = when (throwable) {
        is java.net.SocketTimeoutException,
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> true
        is com.financeautomation.data.remote.GoogleSheetsApiException -> throwable.isRetryable()
        is GoogleDriveApiException -> throwable.isRetryable()
        is java.io.IOException -> {
            throwable !is com.financeautomation.data.remote.SheetNotFoundException &&
            throwable !is com.financeautomation.data.remote.MalformedResponseException &&
            throwable !is com.financeautomation.data.remote.AuthExpiredException &&
            throwable !is com.financeautomation.data.remote.ResponseTooLargeException
        }
        else -> false
    }

    private fun isTransientNetworkFailure(throwable: Throwable?): Boolean = when (throwable) {
        is java.net.SocketTimeoutException,
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.SocketException -> true
        else -> {
            val msg = throwable?.message?.lowercase().orEmpty()
            msg.contains("unknownhost") ||
            msg.contains("unable to resolve host") ||
            msg.contains("failed to connect") ||
            msg.contains("network unreachable") ||
            msg.contains("connection refused") ||
            msg.contains("socket closed")
        }
    }

    override suspend fun updateAccountName(oldName: String, newName: String): Result<Int> = withContext(ioDispatcher) {
        try {
            val dao = resolveTransactionDao()
            val matching = dao.getByAccount(oldName)
            val count = dao.updateAccountName(oldName, newName)
            val syncedTxns = matching.filter { it.sheetRowIndex != null && it.sheetRowIndex >= 4 }
            if (syncedTxns.isNotEmpty()) {
                val qDao = resolveSyncQueueDao()
                val waiting = qDao.getAllQueueFlow().first().map { it.transactionId to it.operationType }.toSet()
                for (txn in syncedTxns) {
                    if (!waiting.contains(txn.id to SyncQueueEntity.OP_INSERT) && !waiting.contains(txn.id to SyncQueueEntity.OP_UPDATE)) {
                        qDao.insert(
                            SyncQueueEntity(
                                transactionId = txn.id,
                                operationType = SyncQueueEntity.OP_UPDATE,
                                payloadJson = txn.id
                            )
                        )
                    }
                }
                requestSync()
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun updateCategoryName(oldName: String, newName: String): Result<Int> = withContext(ioDispatcher) {
        try {
            val dao = resolveTransactionDao()
            val matching = dao.getByCategory(oldName)
            val count = dao.updateCategoryName(oldName, newName)
            val syncedTxns = matching.filter { it.sheetRowIndex != null && it.sheetRowIndex >= 4 }
            if (syncedTxns.isNotEmpty()) {
                val qDao = resolveSyncQueueDao()
                val waiting = qDao.getAllQueueFlow().first().map { it.transactionId to it.operationType }.toSet()
                for (txn in syncedTxns) {
                    if (!waiting.contains(txn.id to SyncQueueEntity.OP_INSERT) && !waiting.contains(txn.id to SyncQueueEntity.OP_UPDATE)) {
                        qDao.insert(
                            SyncQueueEntity(
                                transactionId = txn.id,
                                operationType = SyncQueueEntity.OP_UPDATE,
                                payloadJson = txn.id
                            )
                        )
                    }
                }
                requestSync()
            }
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun updateCompanionName(oldName: String, newName: String): Result<Int> = withContext(ioDispatcher) {
        try {
            val count = resolveTransactionDao().updateCompanionName(oldName, newName)
            Result.Success(count)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private suspend fun markTransportFailure(item: SyncQueueEntity, txn: Transaction, throwable: Throwable? = null) {
        // The transaction keeps its review state: a retryable failure is not a reason to ask the user again
        handleItemTransportFailure(item, throwable)
    }

    /** Called after every sync attempt, successful or not, for example to refresh the home screen widget. */
    var onSyncCompleted: (() -> Unit)? = null

    override suspend fun syncPendingTransactions(): Result<Int> = syncPendingTransactions(userInitiated = false)

    override suspend fun syncPendingTransactions(userInitiated: Boolean): Result<Int> {
        try {
            return runSyncPendingTransactions(userInitiated)
        } finally {
            try {
                onSyncCompleted?.invoke()
            } catch (_: Exception) {
                // A widget refresh must never turn a sync result into a failure
            }
        }
    }

    private suspend fun runSyncPendingTransactions(userInitiated: Boolean): Result<Int> = withContext(ioDispatcher) {
        withSyncLock {
            try {
                if (googleSheetsClient == null || preferencesRepository == null) {
                    return@withSyncLock Result.Success(0)
                }
                // Signed out on purpose: nothing to sync with, and nothing is marked failed. Items wait for the next sign in.
                if (preferencesRepository.getGoogleAccountEmailSync().isNullOrBlank()) {
                    return@withSyncLock Result.Success(0)
                }
                // Google asked the app to wait: make no request at all until the cooldown is over
                if (readGate.isCoolingDown()) {
                    return@withSyncLock Result.Error(
                        com.financeautomation.data.remote.GoogleSheetsApiException(
                            429, "Google asked the app to wait ${readGate.cooldownRemainingMillis() / 1000} seconds; sync will retry"
                        )
                    )
                }

            val transactionDao = resolveTransactionDao()
            val syncQueueDao = resolveSyncQueueDao()

            syncQueueDao.recoverRetryableFailedItems()

            val pendingItems = syncQueueDao.getPendingQueueFifo(maxRetries = MAX_SYNC_RETRIES)
            if (pendingItems.isEmpty()) {
                reconcileIfDue(userInitiated, queueHadWork = false)
                return@withSyncLock Result.Success(0)
            }

            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
            val knownTabsBySpreadsheet = mutableMapOf<String, MutableSet<String>>()

            var syncedCount = 0
            val st = RunState()
            val deliveredMonths = mutableSetOf<java.time.YearMonth>()
            val failedIds = st.failedIds

            for (item in pendingItems) {
                // Items that touch a transaction whose earlier item just failed wait for the next run to keep their order
                if (item.transactionId in failedIds) continue
                try {
                    if (item.operationType == SyncQueueEntity.OP_CONFIG_PUSH) {
                    val configRes = syncConfigUseCase?.push()
                    val targetSheetId = reconcileSpreadsheetId() ?: preferencesRepository?.getSpreadsheetIdSync()
                    if (!targetSheetId.isNullOrBlank()) {
                        try {
                            resolveSubscriptionsSheetSyncCoordinator()?.mirrorToSheet(targetSheetId)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Diagnostics.record("TransactionRepository", "mirrorSubscriptions: ${e.javaClass.simpleName}")
                        }
                        try {
                            resolveTripsSheetSyncCoordinator()?.mirrorToSheet(targetSheetId)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Diagnostics.record("TransactionRepository", "mirrorTrips: ${e.javaClass.simpleName}")
                        }
                    }
                    if (configRes is Result.Success) {
                        syncQueueDao.deleteById(item.id)
                        syncedCount++
                    } else if (configRes is Result.Error) {
                        val err = configRes.exception
                        if (isTransportOrServerError(err)) {
                            val isTransient = isTransientNetworkFailure(err)
                            val newRetries = if (isTransient) item.retryCount else item.retryCount + 1
                            if (newRetries >= MAX_SYNC_RETRIES) {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, newRetries)
                            } else {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_PENDING, newRetries)
                            }
                            st.transportFailure = true
                            break
                        } else {
                            syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, MAX_SYNC_RETRIES)
                        }
                    }
                    continue
                }

                if (item.operationType == SyncQueueEntity.OP_LOG_CREDIT ||
                    item.operationType == SyncQueueEntity.OP_VOID_INFLOW ||
                    item.operationType == SyncQueueEntity.OP_LOG_PAYABLE ||
                    item.operationType == SyncQueueEntity.OP_LOG_REPAYMENT ||
                    item.operationType == SyncQueueEntity.OP_CARRY_FORWARD ||
                    item.operationType == SyncQueueEntity.OP_YEAR_CLOSE ||
                    item.operationType == SyncQueueEntity.OP_RESTORE_INFLOW ||
                    item.operationType == SyncQueueEntity.OP_RESTORE_PAYABLE
                ) {
                    val currentYear = java.time.ZonedDateTime.now(sheetTimeZone).year
                    val opYear = when (item.operationType) {
                        SyncQueueEntity.OP_YEAR_CLOSE -> {
                            AccountingRequestJsonAdapter.yearCloseRequestFromJson(item.payloadJson).year
                        }
                        SyncQueueEntity.OP_LOG_CREDIT -> {
                            val req = AccountingRequestJsonAdapter.creditRequestFromJson(item.payloadJson)
                            Instant.ofEpochMilli(req.timestamp).atZone(sheetTimeZone).year
                        }
                        SyncQueueEntity.OP_VOID_INFLOW -> {
                            val req = AccountingRequestJsonAdapter.voidInflowRequestFromJson(item.payloadJson)
                            Instant.ofEpochMilli(req.timestamp).atZone(sheetTimeZone).year
                        }
                        SyncQueueEntity.OP_LOG_PAYABLE -> {
                            val req = AccountingRequestJsonAdapter.payableRequestFromJson(item.payloadJson)
                            Instant.ofEpochMilli(req.timestamp).atZone(sheetTimeZone).year
                        }
                        SyncQueueEntity.OP_LOG_REPAYMENT -> {
                            val req = AccountingRequestJsonAdapter.repaymentRequestFromJson(item.payloadJson)
                            Instant.ofEpochMilli(req.timestamp).atZone(sheetTimeZone).year
                        }
                        SyncQueueEntity.OP_RESTORE_INFLOW -> {
                            val req = AccountingRequestJsonAdapter.restoreInflowRequestFromJson(item.payloadJson)
                            Instant.ofEpochMilli(req.timestamp).atZone(sheetTimeZone).year
                        }
                        SyncQueueEntity.OP_RESTORE_PAYABLE -> {
                            AccountingRequestJsonAdapter.restorePayableRequestFromJson(item.payloadJson).year
                        }
                        SyncQueueEntity.OP_CARRY_FORWARD -> {
                            val req = AccountingRequestJsonAdapter.carryForwardRequestFromJson(item.payloadJson)
                            req.targetTab.takeLast(2).toIntOrNull()?.let { 2000 + it } ?: currentYear
                        }
                        else -> currentYear
                    }

                    val spreadsheetId = if (workbookResolver != null) {
                        when (val resolveResult = workbookResolver.resolveForYear(opYear)) {
                            is Result.Success -> resolveResult.data
                            is Result.Error -> {
                                val err = resolveResult.exception
                                if (failItem(item, null, err, st)) break else continue
                            }
                            is Result.Loading -> {
                                st.transportFailure = true
                                break
                            }
                        }
                    } else {
                        preferencesRepository.getSpreadsheetIdForYearSync(opYear)
                    }

                    if (spreadsheetId.isNullOrBlank()) {
                        handleItemTransportFailure(item)
                        st.transportFailure = true
                        break
                    }

                    val opTabName = when (item.operationType) {
                        SyncQueueEntity.OP_LOG_CREDIT -> {
                            val req = AccountingRequestJsonAdapter.creditRequestFromJson(item.payloadJson)
                            formatMonthTabName(req.timestamp)
                        }
                        SyncQueueEntity.OP_VOID_INFLOW -> {
                            val req = AccountingRequestJsonAdapter.voidInflowRequestFromJson(item.payloadJson)
                            formatMonthTabName(req.timestamp)
                        }
                        SyncQueueEntity.OP_LOG_PAYABLE -> {
                            val req = AccountingRequestJsonAdapter.payableRequestFromJson(item.payloadJson)
                            formatMonthTabName(req.timestamp)
                        }
                        SyncQueueEntity.OP_LOG_REPAYMENT -> {
                            val req = AccountingRequestJsonAdapter.repaymentRequestFromJson(item.payloadJson)
                            formatMonthTabName(req.timestamp)
                        }
                        SyncQueueEntity.OP_CARRY_FORWARD -> {
                            AccountingRequestJsonAdapter.carryForwardRequestFromJson(item.payloadJson).targetTab
                        }
                        SyncQueueEntity.OP_RESTORE_INFLOW -> {
                            val req = AccountingRequestJsonAdapter.restoreInflowRequestFromJson(item.payloadJson)
                            formatMonthTabName(req.timestamp)
                        }
                        SyncQueueEntity.OP_RESTORE_PAYABLE -> {
                            AccountingRequestJsonAdapter.restorePayableRequestFromJson(item.payloadJson).tabName
                        }
                        else -> "Dashboard"
                    }

                    if (item.operationType != SyncQueueEntity.OP_YEAR_CLOSE && item.operationType != SyncQueueEntity.OP_CARRY_FORWARD) {
                        var knownTabs = knownTabsBySpreadsheet[spreadsheetId]
                        if (knownTabs == null) {
                            val tabsResult = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
                            if (tabsResult is Result.Success) {
                                val tabsSet = tabsResult.data.keys.toMutableSet()
                                knownTabsBySpreadsheet[spreadsheetId] = tabsSet
                                knownTabs = tabsSet
                            }
                        }
                        if (knownTabs != null && !knownTabs.contains(opTabName)) {
                            val ensureRes = googleSheetsClient.ensureMonthTabExists(spreadsheetId, opTabName, accounts, categories)
                            if (ensureRes is Result.Success) {
                                knownTabs.add(opTabName)
                            } else if (ensureRes is Result.Error) {
                                val err = ensureRes.exception
                                handleItemTransportFailure(item, err)
                                st.transportFailure = true
                                if (isRunWideFailure(err)) break else continue
                            }
                        }
                    }

                    val executor = accountingSyncExecutor
                    var payableOutcome: com.financeautomation.data.remote.PayableOutcome? = null
                    val opResult = when (item.operationType) {
                        SyncQueueEntity.OP_LOG_CREDIT -> executor.executeLogCredit(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_VOID_INFLOW -> executor.executeVoidInflow(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_LOG_PAYABLE -> {
                            when (val pRes = executor.executeLogPayable(item, spreadsheetId, opTabName)) {
                                is Result.Success -> {
                                    payableOutcome = pRes.data
                                    Result.Success(Unit)
                                }
                                is Result.Error -> Result.Error(pRes.exception)
                                is Result.Loading -> Result.Loading
                            }
                        }
                        SyncQueueEntity.OP_LOG_REPAYMENT -> executor.executeLogRepayment(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_CARRY_FORWARD -> executor.executeCarryForward(item, spreadsheetId)
                        SyncQueueEntity.OP_YEAR_CLOSE -> executor.executeYearClose(item, spreadsheetId)
                        SyncQueueEntity.OP_RESTORE_INFLOW -> executor.executeRestoreInflow(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_RESTORE_PAYABLE -> executor.executeRestorePayable(item, spreadsheetId, opTabName)
                        else -> Result.Success(Unit)
                    }

                    if (opResult is Result.Success) {
                        if (item.operationType == SyncQueueEntity.OP_LOG_CREDIT) {
                            rememberIfCreditSettledDebts(item.id, item.transactionId)
                        }
                        if (item.operationType == SyncQueueEntity.OP_LOG_PAYABLE) {
                            val payReq = AccountingRequestJsonAdapter.payableRequestFromJson(item.payloadJson)
                            val ts = payReq.timestamp
                            deliveredMonths.add(java.time.YearMonth.from(Instant.ofEpochMilli(ts).atZone(sheetTimeZone)))
                            payReq.transactionId?.let { tid ->
                                transactionDao.updateSyncState(tid, SyncState.SYNCED.name)
                            }
                            payableOutcome?.let { outcome ->
                                resolvePayableDao()?.updateAfterNetting(
                                    uuid = payReq.requestId,
                                    amountIOwe = outcome.netAmount,
                                    status = if (outcome.fullyRepaid) "Repaid" else "Pending",
                                    dateRepaidSerial = if (outcome.fullyRepaid) outcome.dateSerial else null
                                )
                            }
                        }
                        syncQueueDao.deleteById(item.id)
                        syncedCount++
                    } else if (opResult is Result.Error) {
                        val err = opResult.exception
                        if (failItem(item, null, err, st)) break else continue
                    }
                    continue
                }

                val txnEntity = transactionDao.getById(item.transactionId)
                if (txnEntity == null) {
                    syncQueueDao.delete(item)
                    continue
                }

                val txn = txnEntity.toDomain()

                // Skip unconfirmed transactions awaiting user review
                if (txn.reviewState == ReviewState.NEEDS_REVIEW) {
                    continue
                }

                val year = Instant.ofEpochMilli(txn.timestamp).atZone(sheetTimeZone).year
                val spreadsheetId = if (workbookResolver != null) {
                    when (val resolveResult = workbookResolver.resolveForYear(year)) {
                        is Result.Success -> resolveResult.data
                        is Result.Error -> {
                            val err = resolveResult.exception
                            if (failItem(item, txn, err, st)) break else continue
                        }
                        is Result.Loading -> {
                            st.transportFailure = true
                            break
                        }
                    }
                } else {
                    preferencesRepository.getSpreadsheetIdForYearSync(year)
                }

                if (spreadsheetId.isNullOrBlank()) {
                    markTransportFailure(item, txn)
                    st.transportFailure = true
                    break
                }

                val tabName = formatMonthTabName(txn.timestamp)

                // Cache tab list once per spreadsheet per sync run
                var knownTabs = knownTabsBySpreadsheet[spreadsheetId]
                if (knownTabs == null) {
                    when (val tabsResult = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)) {
                        is Result.Success -> {
                            val tabsSet = tabsResult.data.keys.toMutableSet()
                            knownTabsBySpreadsheet[spreadsheetId] = tabsSet
                            knownTabs = tabsSet
                        }
                        is Result.Error -> {
                            val err = tabsResult.exception
                            if (failItem(item, txn, err, st)) break else continue
                        }
                        is Result.Loading -> {
                            st.transportFailure = true
                            break
                        }
                    }
                }

                if (!knownTabs.contains(tabName)) {
                    val tabCheckResult = googleSheetsClient.ensureMonthTabExists(spreadsheetId, tabName, accounts, categories)
                    if (tabCheckResult is Result.Error) {
                        val err = tabCheckResult.exception
                        if (failItem(item, txn, err, st)) break else continue
                    }
                    val wasCreated = (tabCheckResult as Result.Success).data
                    if (wasCreated) {
                        val reqId = "cf_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                        val cfReq = CarryForwardRequest(
                            targetTab = tabName,
                            sourceTab = null,
                            accounts = accounts,
                            categories = categories
                        )
                        val cfQueueItem = SyncQueueEntity(
                            transactionId = reqId,
                            operationType = SyncQueueEntity.OP_CARRY_FORWARD,
                            payloadJson = AccountingRequestJsonAdapter.carryForwardRequestToJson(cfReq)
                        )
                        syncQueueDao.insert(cfQueueItem)
                        val cfRes = accountingSyncExecutor.executeCarryForward(cfQueueItem, spreadsheetId)
                        if (cfRes is Result.Success) {
                            syncQueueDao.deleteById(cfQueueItem.id)
                        }
                    }
                    knownTabs.add(tabName)
                }

                when (item.operationType) {
                    SyncQueueEntity.OP_INSERT -> {
                        val plan: AccountingPlan
                        if (item.planJson != null) {
                            plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
                        } else {
                            // Check if row with this UUID already exists in debit UUID column (AD, or legacy AC on retry)
                            val uuidCheck = findDebitRow(spreadsheetId, tabName, txn.id, checkV1Fallback = (item.retryCount > 0))
                            if (uuidCheck is Result.Success && uuidCheck.data != null) {
                                val existingRow = uuidCheck.data
                                transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name, existingRow)
                                syncQueueDao.delete(item)
                                syncedCount++
                                continue
                            } else if (uuidCheck is Result.Error) {
                                val err = uuidCheck.exception
                                if (failItem(item, txn, err, st)) break else continue
                            }

                            // Check for orphan row (same date, time, amount with blank UUID from interrupted prior run)
                            // A first attempt cannot have left a half written row behind, so skip the two reads it costs
                            val orphanCheck: Result<Int?> = if (item.retryCount == 0) Result.Success(null) else googleSheetsClient.findOrphanDebitRow(
                                spreadsheetId, tabName, txn.dateSerial, txn.timeSerial, txn.totalAmount,
                                account = txn.account, description = txn.merchant
                            )
                            if (orphanCheck is Result.Success && orphanCheck.data != null) {
                                val orphanRow = orphanCheck.data
                                val debitCol = if (googleSheetsClient.getCellString(spreadsheetId, tabName, "L3") is Result.Success &&
                                    (googleSheetsClient.getCellString(spreadsheetId, tabName, "L3") as Result.Success).data.equals("Note", ignoreCase = true)) {
                                    SheetSchemaConstants.COL_LETTER_DEBIT_UUID
                                } else {
                                    SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1
                                }
                                val writeUuidRes = googleSheetsClient.updateCell(spreadsheetId, tabName, "$debitCol$orphanRow", txn.id)
                                if (writeUuidRes is Result.Success) {
                                    transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name, orphanRow)
                                    syncQueueDao.delete(item)
                                    syncedCount++
                                    continue
                                } else {
                                    val err = (writeUuidRes as Result.Error).exception
                                    if (failItem(item, txn, err, st)) break else continue
                                }
                            } else if (orphanCheck is Result.Error) {
                                val err = orphanCheck.exception
                                if (failItem(item, txn, err, st)) break else continue
                            }

                            // Gather open payables if shared
                            var openPayables = emptyList<LedgerPayable>()
                            val companionTokens = AccountingUtils.tokens(txn.companions)
                            if (companionTokens.isNotEmpty() && !txn.companions.equals("Personal", ignoreCase = true)) {
                                val snapRes = googleSheetsClient.readLedgerSnapshot(spreadsheetId)
                                if (snapRes is Result.Success) {
                                    openPayables = snapRes.data.monthTabs.flatMap { month ->
                                        month.payables.map { p ->
                                            LedgerPayable(
                                                tab = month.tabName,
                                                row = p.rowNumber,
                                                uuid = p.uuid,
                                                dateIncurredSerial = p.dateIncurredSerial,
                                                timeSerial = p.timeSerial,
                                                paidBy = p.paidBy,
                                                whatFor = p.whatFor,
                                                amountOwed = p.amountIOwe,
                                                dateRepaidSerial = p.dateRepaidSerial,
                                                status = p.repaymentStatus
                                            )
                                        }
                                    }
                                } else if (snapRes is Result.Error) {
                                    val err = snapRes.exception
                                    if (failItem(item, txn, err, st)) break else continue
                                }
                            }

                            // 2.1: Rebuild sheet row from stored numbers (CUSTOM with myShare = txn.myShare)
                            val splitMode = if (companionTokens.isEmpty() || txn.companions.equals("Personal", ignoreCase = true)) {
                                com.financeautomation.core.model.SplitMode.JUST_ME
                            } else {
                                com.financeautomation.core.model.SplitMode.CUSTOM
                            }

                            plan = calculateSplitUseCase(
                                amount = txn.totalAmount,
                                merchant = txn.merchant,
                                category = txn.category,
                                account = txn.account,
                                splitMode = splitMode,
                                companions = companionTokens,
                                customShare = txn.myShare,
                                tripTag = txn.tripTag,
                                dateSerial = txn.dateSerial ?: com.financeautomation.core.utils.DateSerialUtils.toDateSerial(txn.timestamp),
                                timeSerial = txn.timeSerial ?: com.financeautomation.core.utils.DateSerialUtils.toTimeSerial(txn.timestamp),
                                tabName = tabName,
                                openPayables = openPayables,
                                nettingUseCase = nettingUseCase,
                                uuid = txn.id,
                                note = txn.note
                            )

                            // Store plan before writing anything to guarantee idempotent retries
                            val planJson = AccountingPlanJsonAdapter.toJson(plan)
                            syncQueueDao.updatePlanJson(item.id, planJson)
                        }

                        // The uuid lookup above already showed the row is absent when the plan was just built
                        val applyRes = applyPlan(spreadsheetId, plan, knownAbsent = if (item.planJson == null) setOf(txn.id) else emptySet())
                        if (applyRes is Result.Success) {
                            val rowIndex = applyRes.data ?: txn.sheetRowIndex
                            transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name, rowIndex)
                            syncQueueDao.delete(item)
                            syncedCount++
                            deliveredMonths.add(java.time.YearMonth.from(Instant.ofEpochMilli(txn.timestamp).atZone(sheetTimeZone)))
                        } else {
                            val err = (applyRes as Result.Error).exception
                            if (failItem(item, txn, err, st)) break else continue
                        }
                    }
                    SyncQueueEntity.OP_UPDATE -> {
                        val cachedRow = txn.sheetRowIndex
                        var targetRow: Int? = null

                        if (cachedRow != null && cachedRow >= 4) {
                            val cellUuidRes = googleSheetsClient.getCellString(spreadsheetId, tabName, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}$cachedRow")
                            if (cellUuidRes is Result.Success && cellUuidRes.data.equals(txn.id, ignoreCase = true)) {
                                targetRow = cachedRow
                            } else {
                                val v1Res = googleSheetsClient.getCellString(spreadsheetId, tabName, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}$cachedRow")
                                if (v1Res is Result.Success && v1Res.data.equals(txn.id, ignoreCase = true)) {
                                    targetRow = cachedRow
                                }
                            }
                        }

                        var updateTab = tabName
                        if (targetRow == null) {
                            val scanRes = findDebitRow(spreadsheetId, tabName, txn.id)
                            if (scanRes is Result.Error) {
                                // A network or server error is not "row not found": retry later
                                if (failItem(item, txn, scanRes.exception, st)) break else continue
                            }
                            targetRow = (scanRes as Result.Success).data
                        }

                        if (targetRow == null) {
                            // Not in the computed month tab: the date may have been moved to another month. Look in every month tab.
                            val tabsRes = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
                            if (tabsRes is Result.Error) {
                                if (failItem(item, txn, tabsRes.exception, st)) break else continue
                            }
                            var scanFailure: Throwable? = null
                            for (other in (tabsRes as Result.Success).data.keys.filter { it != tabName && MONTH_TAB_PATTERN.matches(it) }) {
                                val r = findDebitRow(spreadsheetId, other, txn.id)
                                if (r is Result.Error) {
                                    scanFailure = r.exception
                                    break
                                }
                                val found = (r as Result.Success).data
                                if (found != null) {
                                    updateTab = other
                                    targetRow = found
                                    break
                                }
                            }
                            if (scanFailure != null) {
                                if (failItem(item, txn, scanFailure, st)) break else continue
                            }
                        }

                        if (targetRow == null) {
                            markPermanentFailure(
                                item, com.financeautomation.data.remote.SheetNotFoundException("", "Sheet changed: the row was not found"),
                                "Sheet changed: the row was not found"
                            )
                            continue
                        }

                        val payload = SheetPayloadMapper.mapTransactionToTable1(txn)
                        val updateResult = googleSheetsClient.updateRow(spreadsheetId, updateTab, targetRow, payload)
                        if (updateResult is Result.Success) {
                            transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name, targetRow)
                            syncQueueDao.delete(item)
                            syncedCount++
                        } else {
                            val err = (updateResult as Result.Error).exception
                            if (failItem(item, txn, err, st)) break else continue
                        }
                    }
                    SyncQueueEntity.OP_VOID -> {
                        val voidReq = AccountingRequestJsonAdapter.voidExpenseRequestFromJson(item.payloadJson)
                        val deleteSheetRow = voidReq.deleteSheetRow

                        if (deleteSheetRow) {
                            val cachedRow = txn.sheetRowIndex
                            var targetRow: Int? = null

                            if (cachedRow != null && cachedRow >= 4) {
                                val cellUuidRes = googleSheetsClient.getCellString(spreadsheetId, tabName, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}$cachedRow")
                                if (cellUuidRes is Result.Success && cellUuidRes.data.equals(txn.id, ignoreCase = true)) {
                                    targetRow = cachedRow
                                } else {
                                    val v1Res = googleSheetsClient.getCellString(spreadsheetId, tabName, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}$cachedRow")
                                    if (v1Res is Result.Success && v1Res.data.equals(txn.id, ignoreCase = true)) {
                                        targetRow = cachedRow
                                    }
                                }
                            }

                            var voidTab = tabName
                            if (targetRow == null) {
                                val scanRes = findDebitRow(spreadsheetId, tabName, txn.id)
                                if (scanRes is Result.Error) {
                                    if (failItem(item, txn, scanRes.exception, st)) break else continue
                                }
                                targetRow = (scanRes as Result.Success).data
                            }

                            if (targetRow == null) {
                                // Not in the computed month tab: the date may have been edited across a month. Look in every month tab.
                                val tabsRes = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
                                if (tabsRes is Result.Error) {
                                    if (failItem(item, txn, tabsRes.exception, st)) break else continue
                                }
                                var failure: Throwable? = null
                                for (other in (tabsRes as Result.Success).data.keys.filter { it != tabName && MONTH_TAB_PATTERN.matches(it) }) {
                                    val r = findDebitRow(spreadsheetId, other, txn.id)
                                    if (r is Result.Error) {
                                        failure = r.exception
                                        break
                                    }
                                    val found = (r as Result.Success).data
                                    if (found != null) {
                                        voidTab = other
                                        targetRow = found
                                        break
                                    }
                                }
                                if (failure != null) {
                                    if (failItem(item, txn, failure, st)) break else continue
                                }
                            }

                            if (targetRow == null) {
                                transactionDao.deleteById(txn.id)
                                syncQueueDao.delete(item)
                                continue
                            }

                            val isV1 = googleSheetsClient.getCellString(spreadsheetId, voidTab, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}$targetRow")
                                .let { it is Result.Success && it.data.equals(txn.id, ignoreCase = true) }
                            val expenseRange = if (isV1) "'$voidTab'!A$targetRow:K$targetRow" else "'$voidTab'!A$targetRow:L$targetRow"
                            val uuidRange = if (isV1) "'$voidTab'!${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}$targetRow" else "'$voidTab'!${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}$targetRow"
                            val rangesToClear = mutableListOf(expenseRange, uuidRange)

                            val pDao = resolvePayableDao()
                            val linkedPayable = pDao?.getAll()?.firstOrNull { it.uuid == txn.id }
                            if (linkedPayable != null && linkedPayable.sheetRowIndex != null) {
                                val pRow = linkedPayable.sheetRowIndex
                                val pTab = linkedPayable.tabName
                                val payRange = if (isV1) "'$pTab'!${SheetSchemaConstants.TABLE_3_PAYABLES_RANGE_V1.substringBefore(':')}$pRow:${SheetSchemaConstants.TABLE_3_PAYABLES_RANGE_V1.substringAfter(':')}$pRow"
                                               else "'$pTab'!${SheetSchemaConstants.COL_LETTER_PAYABLE_DATE_INCURRED}$pRow:${SheetSchemaConstants.COL_LETTER_PAYABLE_STATUS}$pRow"
                                val payUuidRange = if (isV1) "'$pTab'!${SheetSchemaConstants.COL_LETTER_PAYABLE_UUID_V1}$pRow"
                                                   else "'$pTab'!${SheetSchemaConstants.COL_LETTER_PAYABLE_UUID}$pRow"
                                rangesToClear.add(payRange)
                                rangesToClear.add(payUuidRange)
                            }

                            val clearRes = googleSheetsClient.batchClearValues(spreadsheetId, rangesToClear)
                            if (clearRes is Result.Success) {
                                transactionDao.deleteById(txn.id)
                                if (linkedPayable != null) {
                                    pDao?.deleteByUuid(txn.id)
                                }
                                syncQueueDao.delete(item)
                                syncedCount++
                            } else {
                                val err = (clearRes as Result.Error).exception
                                if (failItem(item, txn, err, st)) break else continue
                            }
                        } else {
                            val plan: AccountingPlan
                            if (item.planJson != null) {
                                plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
                            } else {
                                val cachedRow = txn.sheetRowIndex
                                var targetRow: Int? = null

                                if (cachedRow != null && cachedRow >= 4) {
                                    val cellUuidRes = googleSheetsClient.getCellString(spreadsheetId, tabName, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID}$cachedRow")
                                    if (cellUuidRes is Result.Success && cellUuidRes.data.equals(txn.id, ignoreCase = true)) {
                                        targetRow = cachedRow
                                    } else {
                                        val v1Res = googleSheetsClient.getCellString(spreadsheetId, tabName, "${SheetSchemaConstants.COL_LETTER_DEBIT_UUID_V1}$cachedRow")
                                        if (v1Res is Result.Success && v1Res.data.equals(txn.id, ignoreCase = true)) {
                                            targetRow = cachedRow
                                        }
                                    }
                                }

                                var voidTab = tabName
                                if (targetRow == null) {
                                    val scanRes = findDebitRow(spreadsheetId, tabName, txn.id)
                                    if (scanRes is Result.Error) {
                                        if (failItem(item, txn, scanRes.exception, st)) break else continue
                                    }
                                    targetRow = (scanRes as Result.Success).data
                                }

                                if (targetRow == null) {
                                    // Not in the computed month tab: the date may have been edited across a month. Look in every month tab.
                                    val tabsRes = googleSheetsClient.getSheetTabsWithIds(spreadsheetId)
                                    if (tabsRes is Result.Error) {
                                        if (failItem(item, txn, tabsRes.exception, st)) break else continue
                                    }
                                    var failure: Throwable? = null
                                    for (other in (tabsRes as Result.Success).data.keys.filter { it != tabName && MONTH_TAB_PATTERN.matches(it) }) {
                                        val r = findDebitRow(spreadsheetId, other, txn.id)
                                        if (r is Result.Error) {
                                            failure = r.exception
                                            break
                                        }
                                        val found = (r as Result.Success).data
                                        if (found != null) {
                                            voidTab = other
                                            targetRow = found
                                            break
                                        }
                                    }
                                    if (failure != null) {
                                        if (failItem(item, txn, failure, st)) break else continue
                                    }
                                }

                                if (targetRow == null) {
                                    // Found in no month tab (for example the old workbook was replaced): there is nothing to void
                                    transactionDao.updateSyncState(txn.id, SyncState.VOID.name, txn.sheetRowIndex)
                                    syncQueueDao.delete(item)
                                    continue
                                }

                                val debit = LedgerDebit(
                                    tab = voidTab,
                                    row = targetRow,
                                    uuid = txn.id,
                                    dateSerial = txn.dateSerial,
                                    timeSerial = txn.timeSerial,
                                    account = txn.account,
                                    item = txn.merchant,
                                    tripTag = txn.tripTag,
                                    category = txn.category,
                                    total = txn.totalAmount,
                                    companions = txn.companions,
                                    myShare = txn.myShare,
                                    owed = txn.owedToMe,
                                    status = txn.status.value
                                )
                                plan = voidTransactionUseCase.voidDebit(debit)
                                val planJson = AccountingPlanJsonAdapter.toJson(plan)
                                syncQueueDao.updatePlanJson(item.id, planJson)
                            }

                            val applyRes = applyPlan(spreadsheetId, plan)
                            if (applyRes is Result.Success) {
                                transactionDao.updateSyncState(txn.id, SyncState.VOID.name, txn.sheetRowIndex)
                                syncQueueDao.delete(item)
                                syncedCount++
                            } else {
                                val err = (applyRes as Result.Error).exception
                                if (failItem(item, txn, err, st)) break else continue
                            }
                        }
                    }
                }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Diagnostics.record("TransactionRepositoryImpl", "${e.javaClass.simpleName}: sync item ${item.id} (${item.operationType}) failed: ${e.message}")
                    failItem(item, null, e, st)
                    continue
                }
            }

            enqueueCatchUpCarries(deliveredMonths)
            if (st.authExpired) {
                Result.Error(com.financeautomation.data.remote.AuthExpiredException("Sign-in expired; the queue is paused until you reconnect"))
            } else if (st.transportFailure) {
                Result.Error(com.financeautomation.data.remote.GoogleSheetsException("Transport failure occurred during synchronization; scheduled for exponential backoff retry"))
            } else {
                reconcileIfDue(userInitiated, queueHadWork = syncedCount > 0)
                Result.Success(syncedCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}

    private val lastSeenModified = java.util.concurrent.ConcurrentHashMap<String, String>()

    private suspend fun reconcileSpreadsheetId(): String? {
        val currentYear = java.time.ZonedDateTime.now(sheetTimeZone).year
        return when (val res = workbookResolver?.resolveForYear(currentYear)) {
            is Result.Success -> res.data
            else -> preferencesRepository?.getSpreadsheetIdSync()
        }
    }

    /** The month tabs a routine reconcile reads: the current one and the one before, in Asia/Kolkata. */
    private fun recentTabs(): Set<String> {
        val now = Instant.ofEpochMilli(clock()).atZone(sheetTimeZone)
        return setOf(now, now.minusMonths(1)).map { it.format(DateTimeFormatter.ofPattern("MMMyy", Locale.US)) }.toSet()
    }

    /**
     * Runs a reconcile only when it is worth the read quota: the minimum interval has passed (a user action may bypass
     * it once a minute), and either this run delivered queue items or the Drive modified time changed.
     */
    private suspend fun reconcileIfDue(force: Boolean, queueHadWork: Boolean) {
        if (!readGate.canRun(RECONCILE_KEY, SheetsReadGate.RECONCILE_INTERVAL_MS, force)) return
        val id = reconcileSpreadsheetId() ?: return
        val provider = modifiedTimeProvider
        var modified: String? = null
        if (provider != null) {
            modified = (provider(id) as? Result.Success)?.data
            val unchanged = modified != null && modified == lastSeenModified[id]
            if (unchanged && !queueHadWork && !force) return
        }
        if (!readGate.tryAcquire(RECONCILE_KEY, SheetsReadGate.RECONCILE_INTERVAL_MS, force)) return
        if (reconcileWithSheet(id) is Result.Success && modified != null) lastSeenModified[id] = modified
    }

    override suspend fun reconcileAllMonths(): Result<Int> = withContext(ioDispatcher) {
        if (!readGate.tryAcquire(CHECK_ALL_MONTHS_KEY, CHECK_ALL_MONTHS_COOLDOWN_MS, force = false)) {
            return@withContext Result.Error("Please wait 10 minutes before checking all months again")
        }
        val spreadsheetId = reconcileSpreadsheetId() ?: return@withContext Result.Success(0)
        reconcileWithSheet(spreadsheetId, allTabs = true)
    }

    /** [tabs] limits the read; the default is the current and the previous month. [allTabs] bypasses filtering. */
    suspend fun reconcileWithSheet(
        targetSpreadsheetId: String? = null,
        tabs: Set<String>? = null,
        allTabs: Boolean = false
    ): Result<Int> = when (val res = reconcileWithSheetOutcome(targetSpreadsheetId, tabs, allTabs)) {
        is Result.Success -> Result.Success(res.data.reconciled)
        is Result.Error -> Result.Error(res.exception)
        is Result.Loading -> Result.Loading
    }

    /**
     * The sheet is the source of truth and Room is a cache: this updates local rows from the sheet, imports sheet rows
     * missing locally, rebuilds payables and removes synced local rows the sheet no longer holds (see
     * [pruneMissingFromSheet]). The outcome carries how many entries were pruned.
     */
    suspend fun reconcileWithSheetOutcome(
        targetSpreadsheetId: String? = null,
        tabs: Set<String>? = null,
        allTabs: Boolean = false
    ): Result<ReconcileOutcome> = withContext(ioDispatcher) {
        try {
            val sheets = googleSheetsClient ?: return@withContext Result.Success(ReconcileOutcome(0))
            val tDao = resolveTransactionDao()
            val pDao = resolvePayableDao()

            val spreadsheetId = targetSpreadsheetId ?: reconcileSpreadsheetId()
                ?: return@withContext Result.Success(ReconcileOutcome(0))

            try {
                resolveSubscriptionsSheetSyncCoordinator()?.syncSubscriptionsTab(spreadsheetId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("TransactionRepository", "syncSubscriptionsTab: ${e.javaClass.simpleName}")
            }
            try {
                resolveTripsSheetSyncCoordinator()?.syncTripsTab(spreadsheetId)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Diagnostics.record("TransactionRepository", "syncTripsTab: ${e.javaClass.simpleName}")
            }

            // Only rows that were fully delivered before the read began can be judged missing: a row delivered while
            // the read was running may simply be newer than the snapshot
            val pruneCandidates = collectPruneCandidates()

            val snapRes = if (allTabs) {
                sheets.readLedgerSnapshot(spreadsheetId)
            } else {
                val readTabs = (tabs ?: recentTabs()).map { it.lowercase() }.toSet()
                sheets.readLedgerSnapshot(spreadsheetId) { it.lowercase() in readTabs }
            }
            if (snapRes is Result.Error) {
                return@withContext Result.Error(snapRes.exception)
            }
            val snapshot = (snapRes as Result.Success).data
            var reconciledCount = 0
            // Ids of local rows the sheet holds (matched by uuid, or by tab and row for a sheet row without a uuid)
            val seenLocalIds = mutableSetOf<String>()
            var prunedCount = 0
            var manyMissing = false

            val remotePayables = mutableListOf<PayableEntity>()
            val queuedIds = resolveSyncQueueDao().getAllQueueFlow().first().map { it.transactionId }.toSet()

            runInDbTransaction {
                for (month in snapshot.monthTabs) {
                    for (sheetDebit in month.debits) {
                        val uuid = sheetDebit.uuid
                        // Row number matching is only safe for a sheet row without a uuid. A row that carries a uuid
                        // we do not know belongs to another transaction, even when its row number is cached locally.
                        val localTxn = if (!uuid.isNullOrBlank()) {
                            tDao.getById(uuid)
                        } else {
                            tDao.getByTabAndSheetRowIndex(month.tabName, sheetDebit.rowNumber)
                        }

                        if (localTxn != null) seenLocalIds.add(localTxn.id)

                        // A change this device has not delivered yet is newer than the sheet: never overwrite it
                        if (localTxn != null) {
                            val valuesDiffer = localTxn.totalAmount != sheetDebit.totalAmount ||
                                localTxn.merchant != sheetDebit.description ||
                                localTxn.category != sheetDebit.category ||
                                localTxn.myShare != sheetDebit.myShare ||
                                localTxn.owedToMe != sheetDebit.owedToMe ||
                                localTxn.status != sheetDebit.status ||
                                localTxn.companions != sheetDebit.companions
                            val unsyncedEdit = localTxn.syncState == SyncState.PENDING.name && valuesDiffer
                            if (localTxn.id in queuedIds || unsyncedEdit) continue
                        }

                        if (localTxn != null) {
                            val newTotal = sheetDebit.totalAmount
                            val newMerchant = sheetDebit.description
                            val newCategory = sheetDebit.category
                            val newMyShare = sheetDebit.myShare
                            val newOwed = sheetDebit.owedToMe
                            val newStatus = sheetDebit.status
                            val newCompanions = sheetDebit.companions
                            val newRow = sheetDebit.rowNumber

                            if (localTxn.totalAmount != newTotal ||
                                localTxn.merchant != newMerchant ||
                                localTxn.category != newCategory ||
                                localTxn.myShare != newMyShare ||
                                localTxn.owedToMe != newOwed ||
                                localTxn.status != newStatus ||
                                localTxn.companions != newCompanions ||
                                localTxn.sheetRowIndex != newRow
                            ) {
                                tDao.update(
                                    localTxn.copy(
                                        totalAmount = newTotal,
                                        merchant = newMerchant,
                                        category = newCategory,
                                        myShare = newMyShare,
                                        owedToMe = newOwed,
                                        status = newStatus,
                                        companions = newCompanions,
                                        sheetRowIndex = newRow,
                                        syncState = SyncState.SYNCED.name
                                    )
                                )
                                reconciledCount++
                            }
                        } else {
                            // A voided row on the sheet is history; never import it as a new local row
                            if (sheetDebit.description.startsWith("[Void]", ignoreCase = true) ||
                                sheetDebit.status.orEmpty().equals("Void", ignoreCase = true)
                            ) {
                                continue
                            }
                            // 2.7: Sheet as source of truth: import debits by UUID into Room when local DB empty
                            val effectiveUuid = if (!uuid.isNullOrBlank()) uuid else "sheet_${month.tabName}_${sheetDebit.rowNumber}"
                            val existingByEffective = tDao.getById(effectiveUuid)
                            if (existingByEffective != null) {
                                tDao.update(
                                    existingByEffective.copy(
                                        totalAmount = sheetDebit.totalAmount,
                                        merchant = sheetDebit.description,
                                        category = sheetDebit.category,
                                        myShare = sheetDebit.myShare,
                                        owedToMe = sheetDebit.owedToMe,
                                        status = sheetDebit.status,
                                        companions = sheetDebit.companions,
                                        sheetRowIndex = sheetDebit.rowNumber,
                                        syncState = SyncState.SYNCED.name
                                    )
                                )
                                reconciledCount++
                            } else {
                                val effectiveDateSerial = sheetDebit.dateSerial ?: DateSerialUtils.toDateSerial(System.currentTimeMillis())
                                val effectiveTimeSerial = sheetDebit.timeSerial ?: DateSerialUtils.toTimeSerial(System.currentTimeMillis())
                                val ld = DateSerialUtils.fromDateSerial(effectiveDateSerial)
                                val lt = DateSerialUtils.fromTimeSerial(effectiveTimeSerial)
                                val txnTimestamp = ld.atTime(lt).atZone(sheetTimeZone).toInstant().toEpochMilli()

                                val imported = TransactionEntity(
                                    id = effectiveUuid,
                                    timestamp = txnTimestamp,
                                    dateSerial = effectiveDateSerial,
                                    timeSerial = effectiveTimeSerial,
                                    merchant = sheetDebit.description.ifBlank { "Expense" },
                                    category = sheetDebit.category.ifBlank { com.financeautomation.core.constants.CategoryConstants.CATEGORY_OTHER },
                                    account = sheetDebit.account.ifBlank { "Cash" },
                                    totalAmount = sheetDebit.totalAmount,
                                    myShare = sheetDebit.myShare,
                                    owedToMe = sheetDebit.owedToMe,
                                    companions = sheetDebit.companions.orEmpty().ifBlank { "Personal" },
                                    status = sheetDebit.status.orEmpty().ifBlank { com.financeautomation.core.model.TransactionStatus.PERSONAL.value },
                                    tripTag = sheetDebit.tripTag.orEmpty(),
                                    sheetRowIndex = sheetDebit.rowNumber,
                                    syncState = SyncState.SYNCED.name,
                                    reviewState = ReviewState.CONFIRMED.name
                                )
                                tDao.insert(imported)
                                reconciledCount++
                            }
                        }
                    }

                    for (sheetPayable in month.payables) {
                        val pUuid = sheetPayable.uuid ?: continue
                        remotePayables.add(
                            PayableEntity(
                                uuid = pUuid,
                                dateIncurredSerial = sheetPayable.dateIncurredSerial,
                                timeSerial = sheetPayable.timeSerial,
                                paidBy = sheetPayable.paidBy,
                                whatFor = sheetPayable.whatFor,
                                amountIOwe = sheetPayable.amountIOwe,
                                dateRepaidSerial = sheetPayable.dateRepaidSerial,
                                repaymentStatus = sheetPayable.repaymentStatus,
                                tabName = month.tabName,
                                sheetRowIndex = sheetPayable.rowNumber,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }

                if (pDao != null) {
                    val remoteUuids = remotePayables.map { it.uuid }.toSet()
                    val localPayables = pDao.getAll()
                    // Only tabs that were read can tell us a payable is gone
                    val readTabNames = snapshot.monthTabs.map { it.tabName.lowercase() }.toSet()
                    for (local in localPayables) {
                        if (local.tabName.lowercase() in readTabNames && !remoteUuids.contains(local.uuid)) {
                            pDao.deleteByUuid(local.uuid)
                        }
                    }
                    if (remotePayables.isNotEmpty()) {
                        pDao.upsertAll(remotePayables)
                    }
                }
            }

            for (month in snapshot.monthTabs) {
                for (sheetInflow in month.inflows) {
                    sheetInflow.uuid?.takeIf { it.isNotBlank() }?.let { seenLocalIds.add(it) }
                }
            }
            val prune = pruneMissingFromSheet(spreadsheetId, snapshot, seenLocalIds, pruneCandidates)
            prunedCount = prune.pruned
            manyMissing = prune.manyMissing

            Result.Success(ReconcileOutcome(reconciledCount, prunedCount, manyMissing))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            com.financeautomation.core.utils.Diagnostics.record(
                "TransactionRepositoryImpl",
                "reconcileWithSheet failed: ${e.message}"
            )
            Result.Error(e)
        }
    }

    private class PruneCandidates(
        val transactions: Map<String, TransactionEntity>,
        val inflows: Map<String, InflowEntity>
    )

    private class PruneResult(val pruned: Int, val manyMissing: Boolean)

    /** True for a row the sheet has acknowledged: delivered, confirmed, with a sheet row, and no pending change. */
    private fun isDeliveredAndSettled(t: TransactionEntity): Boolean {
        if (t.sheetRowIndex == null || t.reviewState != ReviewState.CONFIRMED.name) return false
        return t.syncState == SyncState.SYNCED.name ||
            (t.syncState == SyncState.VOID.name && t.status.equals(TransactionStatus.VOID.value, ignoreCase = true))
    }

    /**
     * Local rows that could be judged missing from the sheet: fully synced and with nothing in the sync queue in any
     * state. Taken before the sheet is read, so a row delivered while the read runs is never a candidate.
     */
    private suspend fun collectPruneCandidates(): PruneCandidates {
        val queued = resolveSyncQueueDao().getAllQueueFlow().first().map { it.transactionId }.toSet()
        val txns = resolveTransactionDao().getTransactionsFlow().first()
            .filter { it.id !in queued && isDeliveredAndSettled(it) }
            .associateBy { it.id }
        val inflows = resolveInflowDao()?.getAllInflows()?.first().orEmpty()
            .filter {
                it.id !in queued &&
                    (it.status.equals("CONFIRMED", ignoreCase = true) || it.status.equals("VOID", ignoreCase = true))
            }
            .associateBy { it.id }
        return PruneCandidates(txns, inflows)
    }

    private fun tabNameOf(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(sheetTimeZone).format(DateTimeFormatter.ofPattern("MMMyy", Locale.US)).lowercase()

    /**
     * Removes synced local rows (expenses and income) that the sheet no longer holds, after a read that succeeded for
     * every tab asked for. Nothing is removed when:
     * - a workbook replacement is still being requeued, or the workbook is not one that already acknowledged this
     *   device's rows (the confirmed workbook marker differs, or there is none and no local row was found in it);
     * - the row is queued, pending, failed, in review or edited since the read began;
     * - the row belongs to a month tab that was not part of the read.
     * Safety brake: more than half of the synced rows and more than [PRUNE_BRAKE_MIN_ROWS] missing at once is held
     * back and flagged instead. Payables rebuild from the sheet in the same reconcile; people balances and trip
     * totals are derived from the remaining rows; a paid subscription cycle that pointed at a removed row is closed.
     */
    private suspend fun pruneMissingFromSheet(
        spreadsheetId: String,
        snapshot: com.financeautomation.data.remote.LedgerWorkbookSnapshot,
        seenLocalIds: Set<String>,
        candidates: PruneCandidates
    ): PruneResult {
        val prefs = preferencesRepository ?: return PruneResult(0, false)
        val confirmed = prefs.getPreference(PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID)
        val requeuePending = !prefs.getPreference(PreferencesRepository.KEY_WORKBOOK_REQUEUE_PENDING_YEAR).isNullOrBlank()
        val matched = candidates.transactions.keys.count { it in seenLocalIds } +
            candidates.inflows.keys.count { it in seenLocalIds }

        val allowed = !requeuePending &&
            (confirmed == spreadsheetId || (confirmed.isNullOrBlank() && matched > 0))
        // A workbook that holds some of our rows has acknowledged them; from the next pass it may be pruned against
        if (!requeuePending && matched > 0 && confirmed != spreadsheetId) {
            prefs.setPreference(PreferencesRepository.KEY_LEDGER_CONFIRMED_WORKBOOK_ID, spreadsheetId, isDirty = false)
        }
        if (!allowed) return PruneResult(0, false)

        val readTabs = snapshot.monthTabs.map { it.tabName.lowercase() }.toSet()
        val missingTxns = candidates.transactions.values
            .filter { tabNameOf(it.timestamp) in readTabs && it.id !in seenLocalIds }
        val missingInflows = candidates.inflows.values
            .filter { tabNameOf(it.timestamp) in readTabs && it.id !in seenLocalIds }
        val missing = missingTxns.size + missingInflows.size
        val syncedTotal = candidates.transactions.size + candidates.inflows.size

        if (missing == 0) {
            prefs.setPreference(PreferencesRepository.KEY_SHEET_MANY_MISSING_FLAG, null, isDirty = false)
            return PruneResult(0, false)
        }
        if (missing * 2 > syncedTotal && missing > PRUNE_BRAKE_MIN_ROWS) {
            Diagnostics.record("TransactionRepository", "SHEET_PRUNE_HELD_BACK missing=$missing synced=$syncedTotal")
            prefs.setPreference(PreferencesRepository.KEY_SHEET_MANY_MISSING_FLAG, "1", isDirty = false)
            return PruneResult(0, true)
        }

        val tDao = resolveTransactionDao()
        val qDao = resolveSyncQueueDao()
        val iDao = resolveInflowDao()
        val removedTxnIds = mutableListOf<String>()
        val removedInflowIds = mutableListOf<String>()
        val cycleDao = subscriptionCycleDao ?: databaseProvider?.invoke()?.subscriptionCycleDao()
        runInDbTransaction {
            val queuedNow = qDao.getAllQueueFlow().first().map { it.transactionId }.toSet()
            for (candidate in missingTxns) {
                val current = tDao.getById(candidate.id) ?: continue
                // Changed or queued since the read began: it is no longer a settled row
                if (current != candidate || current.id in queuedNow) continue
                tDao.deleteById(current.id)
                qDao.deleteByTransactionId(current.id)
                removedTxnIds.add(current.id)
            }
            if (iDao != null) {
                for (candidate in missingInflows) {
                    val current = iDao.getById(candidate.id) ?: continue
                    if (current != candidate || current.id in queuedNow) continue
                    iDao.deleteById(current.id)
                    qDao.deleteByTransactionId(current.id)
                    removedInflowIds.add(current.id)
                }
            }
            if (cycleDao != null && removedTxnIds.isNotEmpty()) {
                removedTxnIds.chunked(SQLITE_IN_CHUNK).forEach { cycleDao.releaseTransactions(it, clock()) }
            }
        }
        removedInflowIds.forEach { prefs.setPreference(SettledInflowMarker.key(it), null, isDirty = false) }

        val pruned = removedTxnIds.size + removedInflowIds.size
        prefs.setPreference(PreferencesRepository.KEY_SHEET_MANY_MISSING_FLAG, null, isDirty = false)
        if (pruned > 0) {
            Diagnostics.record("TransactionRepository", "SHEET_PRUNE removed=$pruned")
            val pending = prefs.getPreference(PreferencesRepository.KEY_SHEET_PRUNED_PENDING_COUNT)?.toIntOrNull() ?: 0
            prefs.setPreference(PreferencesRepository.KEY_SHEET_PRUNED_PENDING_COUNT, (pending + pruned).toString(), isDirty = false)
        }
        return PruneResult(pruned, false)
    }

    override suspend fun takeSheetPruneNotice(): com.financeautomation.domain.model.SheetPruneNotice = withContext(ioDispatcher) {
        val prefs = preferencesRepository ?: return@withContext com.financeautomation.domain.model.SheetPruneNotice()
        val pending = prefs.getPreference(PreferencesRepository.KEY_SHEET_PRUNED_PENDING_COUNT)?.toIntOrNull() ?: 0
        if (pending > 0) prefs.setPreference(PreferencesRepository.KEY_SHEET_PRUNED_PENDING_COUNT, null, isDirty = false)
        com.financeautomation.domain.model.SheetPruneNotice(
            pruned = pending,
            manyMissing = prefs.getPreference(PreferencesRepository.KEY_SHEET_MANY_MISSING_FLAG) == "1"
        )
    }

    private suspend fun applyPlan(
        spreadsheetId: String,
        plan: AccountingPlan,
        knownAbsent: Set<String> = emptySet()
    ): Result<Int?> {
        val sheets = googleSheetsClient ?: return Result.Error(IllegalStateException("GoogleSheetsClient unavailable"))
        var lastAppendedRow: Int? = null
        var debitRow: Int? = null

        // Step 1: Appends first (with UUID in second step)
        for (append in plan.appends) {
            val colUuid = when (append.table) {
                TableType.DEBIT -> SheetSchemaConstants.COL_LETTER_DEBIT_UUID
                TableType.INFLOW -> SheetSchemaConstants.COL_LETTER_INFLOW_UUID
                TableType.PAYABLE -> SheetSchemaConstants.COL_LETTER_PAYABLE_UUID
            }
            val existingCheck: Result<Int?> = if (append.uuid in knownAbsent) Result.Success(null) else sheets.findRowByUuid(spreadsheetId, append.tab, colUuid, append.uuid)
            if (existingCheck is Result.Success && existingCheck.data != null) {
                val row = existingCheck.data
                lastAppendedRow = row
                if (append.table == TableType.DEBIT) debitRow = row
                continue
            } else if (existingCheck is Result.Error) {
                // Never append when we could not tell whether the row already exists: that can duplicate it
                return Result.Error(existingCheck.exception)
            }

            val appendRes = when (append.table) {
                TableType.DEBIT -> sheets.appendDebit(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
                TableType.INFLOW -> sheets.appendInflow(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
                TableType.PAYABLE -> sheets.appendPayable(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
            }

            if (appendRes is Result.Success) {
                val row = appendRes.data
                lastAppendedRow = row
                if (append.table == TableType.DEBIT) debitRow = row
            } else {
                val err = (appendRes as Result.Error).exception
                return Result.Error(err)
            }
        }

        // Step 2: Apply edits with batchUpdateValues
        if (plan.edits.isNotEmpty()) {
            val rangesWithValues = plan.edits.map { edit ->
                "'${edit.tab}'!${edit.column}${edit.row}" to listOf(listOf(edit.value))
            }
            val editRes = sheets.batchUpdateValues(spreadsheetId, rangesWithValues.toMap())
            if (editRes is Result.Error) {
                return Result.Error(editRes.exception)
            }
        }

        // Step 3: Apply plan edits and settled references locally to Room database via executor
        accountingSyncExecutor.applyPlanToLocalDatabase(plan)

        return Result.Success(debitRow ?: lastAppendedRow)
    }

    /** Called once a credit reached the sheet: notes whether it settled any debt, from the plan stored on its queue row. */
    private suspend fun rememberIfCreditSettledDebts(queueId: Long, inflowId: String) {
        try {
            val planJson = resolveSyncQueueDao().getById(queueId)?.planJson ?: return
            if (AccountingPlanJsonAdapter.fromJson(planJson).settled.isNotEmpty()) {
                preferencesRepository?.setPreference(SettledInflowMarker.key(inflowId), "1", isDirty = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            com.financeautomation.core.utils.Diagnostics.record("TransactionRepositoryImpl", "${e.javaClass.simpleName}: settled marker")
        }
    }

    /**
     * A debt delivered to a month that is already over would stay there. Queue a carry for each month after it, up to
     * the current one; carrying is idempotent per row, so repeating one is harmless.
     */
    private suspend fun enqueueCatchUpCarries(deliveredMonths: Set<java.time.YearMonth>) {
        if (deliveredMonths.isEmpty()) return
        val current = java.time.YearMonth.from(Instant.ofEpochMilli(clock()).atZone(sheetTimeZone))
        val formatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
        val wanted = linkedSetOf<Pair<String, String>>()
        for (month in deliveredMonths.filter { it < current }) {
            var source = month
            var steps = 0
            while (source < current && steps < 12) {
                val target = source.plusMonths(1)
                wanted.add(target.atDay(1).format(formatter) to source.atDay(1).format(formatter))
                source = target
                steps++
            }
        }
        if (wanted.isEmpty()) return
        val qDao = resolveSyncQueueDao()
        val waiting = qDao.getAllQueueFlow().first()
            .filter { it.operationType == SyncQueueEntity.OP_CARRY_FORWARD }
            .map { AccountingRequestJsonAdapter.carryForwardRequestFromJson(it.payloadJson) }
            .map { it.targetTab to it.sourceTab }
            .toSet()
        val accs = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
        val cats = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
        for ((target, source) in wanted) {
            if ((target to source) in waiting) continue
            val req = CarryForwardRequest(targetTab = target, sourceTab = source, accounts = accs, categories = cats)
            qDao.insert(
                SyncQueueEntity(
                    transactionId = "cf_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                    operationType = SyncQueueEntity.OP_CARRY_FORWARD,
                    payloadJson = AccountingRequestJsonAdapter.carryForwardRequestToJson(req)
                )
            )
        }
        requestSync()
    }

    private class RunState {
        var transportFailure = false
        var authExpired = false
        val failedIds = mutableSetOf<String>()
    }

    /**
     * Records the outcome of one failed queue item and returns true when the whole run must stop.
     *
     * - Expired sign-in: nothing is damaged, the item stays as it was and is marked AUTH_EXPIRED so the reconnect
     *   message can show; the run stops and resumes after sign-in.
     * - Retryable (network, 5xx, 408, 429, rate-limit 403): one retry is counted; later items of the same
     *   transaction wait, unrelated items still run unless the whole run should back off.
     * - Anything else is permanent for this item.
     */
    private suspend fun failItem(item: SyncQueueEntity, txn: Transaction?, err: Throwable, st: RunState): Boolean {
        val qDao = resolveSyncQueueDao()
        if (err is com.financeautomation.data.remote.AuthExpiredException) {
            st.authExpired = true
            qDao.setStatusError(item.id, item.status, item.retryCount, SyncErrorKind.AUTH_EXPIRED.name, err.message)
            return true
        }
        val isMissingTab = err is com.financeautomation.data.remote.SheetNotFoundException ||
            err.message?.contains("unable to parse range", ignoreCase = true) == true
        if (isTransportOrServerError(err) || isMissingTab) {
            st.transportFailure = true
            st.failedIds.add(item.transactionId)
            if (txn != null) markTransportFailure(item, txn, err) else handleItemTransportFailure(item, err)
            return isRunWideFailure(err)
        }
        markPermanentFailure(item, err)
        return false
    }

    /**
     * A permanent failure is shown as a failed queue item with its reason. The transaction keeps its review state, so a
     * row the user already confirmed does not come back to Needs Review.
     */
    private suspend fun markPermanentFailure(item: SyncQueueEntity, err: Throwable?, fallbackDetail: String? = null) {
        val kind = SyncErrorKind.fromThrowable(err).name
        val detail = err?.message ?: fallbackDetail ?: err?.javaClass?.simpleName ?: "Permanent failure"
        resolveSyncQueueDao().setStatusError(item.id, SyncQueueEntity.STATUS_FAILED, 999, kind, com.financeautomation.core.utils.PrivacyScrubber.scrub(detail))
    }

    private fun isRunWideFailure(err: Throwable): Boolean = when (err) {
        is java.net.SocketTimeoutException,
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> true
        is com.financeautomation.data.remote.GoogleSheetsApiException -> err.isRunWideBackoff()
        is GoogleDriveApiException -> err.isRetryable()
        else -> false
    }

    private suspend fun handleItemTransportFailure(item: SyncQueueEntity, throwable: Throwable? = null) {
        val qDao = resolveSyncQueueDao()
        val isTransient = isTransientNetworkFailure(throwable)
        val newRetries = if (isTransient) item.retryCount else item.retryCount + 1
        val kind = SyncErrorKind.fromThrowable(throwable).name
        val detail = com.financeautomation.core.utils.PrivacyScrubber.scrub(throwable?.message ?: throwable?.javaClass?.simpleName ?: "Transport failure")
        if (newRetries >= MAX_SYNC_RETRIES) {
            qDao.setStatusError(item.id, SyncQueueEntity.STATUS_FAILED, newRetries, kind, detail)
        } else {
            qDao.setStatusError(item.id, SyncQueueEntity.STATUS_PENDING, newRetries, kind, detail)
        }
    }

    private fun formatMonthTabName(timestamp: Long): String {
        val dateTime = Instant.ofEpochMilli(timestamp).atZone(sheetTimeZone)
        val formatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
        return dateTime.format(formatter)
    }

    companion object {
        private val MONTH_TAB_PATTERN = Regex("^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[0-9]{2}$", RegexOption.IGNORE_CASE)
        const val SQLITE_IN_CHUNK = 500
        /** A bulk removal of more than this many rows (and over half the synced rows) is held back. */
        const val PRUNE_BRAKE_MIN_ROWS = 20
        const val MAX_SYNC_RETRIES: Int = 8
        private const val RECONCILE_KEY = "reconcile"
        private const val CHECK_ALL_MONTHS_KEY = "check_all_months"
        const val CHECK_ALL_MONTHS_COOLDOWN_MS = 10 * 60 * 1000L
        const val AMOUNT_MESSAGE: String = "Enter an amount greater than zero"
    }
}
