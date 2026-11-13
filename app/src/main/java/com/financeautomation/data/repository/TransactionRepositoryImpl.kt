package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
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
import com.financeautomation.data.remote.GoogleSheetsClient
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
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
        ioDispatcher = ioDispatcher
    )

    private val syncMutex = Mutex()

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
            if (isFuture(transaction.timestamp)) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
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
        try {
            if (isFuture(transaction.timestamp)) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
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
            if (isFuture(timestamp)) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
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
        timestamp: Long
    ): Result<String> = withContext(ioDispatcher) {
        try {
            if (!amount.isFinite() || amount <= 0.0) return@withContext Result.Error(IllegalArgumentException(AMOUNT_MESSAGE))
            if (isFuture(timestamp)) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            val reqId = "pay_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val req = LogPayableRequest(
                requestId = reqId,
                creditor = creditor,
                amount = amount,
                whatFor = whatFor,
                timestamp = timestamp
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

    override suspend fun logRepayment(
        creditor: String,
        amount: Double,
        explicitRef: String?,
        timestamp: Long
    ): Result<String> = withContext(ioDispatcher) {
        try {
            if (!amount.isFinite() || amount <= 0.0) return@withContext Result.Error(IllegalArgumentException(AMOUNT_MESSAGE))
            if (isFuture(timestamp)) return@withContext Result.Error(TimeGuardConstants.FUTURE_DATE_MESSAGE)
            val reqId = "rep_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val req = LogRepaymentRequest(
                requestId = reqId,
                creditor = creditor,
                amount = amount,
                explicitRef = explicitRef,
                timestamp = timestamp
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

    override suspend fun voidTransaction(id: String): Result<Unit> = withContext(ioDispatcher) {
        // A running sync may be delivering this very row; wait for it so the decision below sees the final queue
        syncMutex.withLock { voidTransactionLocked(id) }
    }

    private suspend fun voidTransactionLocked(id: String): Result<Unit> {
        try {
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val existing = tDao.getById(id)
            if (existing == null) {
                val iDao = resolveInflowDao()
                val existingInflow = iDao?.getById(id)
                if (existingInflow != null) {
                    return voidInflowLocked(id, iDao)
                }
                return Result.Error(IllegalArgumentException("Transaction not found: $id"))
            }

            val voidTitle = if (existing.merchant.startsWith("[Void] ")) {
                existing.merchant
            } else {
                "[Void] ${existing.merchant}"
            }

            // Never delivered: there is nothing on the sheet to void, so drop its queue items and void it here only
            val neverDelivered = existing.sheetRowIndex == null &&
                qDao.getAllQueueFlow().first().any { it.transactionId == id && it.operationType == SyncQueueEntity.OP_INSERT }
            if (neverDelivered) {
                runInDbTransaction {
                    qDao.deleteByTransactionId(id)
                    tDao.voidTransaction(id, voidTitle)
                }
                requestSync()
                return Result.Success(Unit)
            }

            runInDbTransaction {
                tDao.voidTransaction(id, voidTitle)

                val queueItem = SyncQueueEntity(
                    transactionId = id,
                    operationType = SyncQueueEntity.OP_VOID,
                    payloadJson = id
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
    private suspend fun voidInflowLocked(id: String, iDao: InflowDao): Result<Unit> {
        val qDao = resolveSyncQueueDao()
        val inflow = iDao.getById(id) ?: return Result.Error(IllegalArgumentException("Transaction not found: $id"))
        val queued = qDao.getAllQueueFlow().first().filter { it.transactionId == id }
        if (queued.any { it.operationType == SyncQueueEntity.OP_LOG_CREDIT }) {
            runInDbTransaction {
                qDao.deleteByTransactionId(id)
                iDao.updateStatus(id, "VOID")
            }
            return Result.Success(Unit)
        }
        if (queued.any { it.operationType == SyncQueueEntity.OP_VOID_INFLOW }) {
            iDao.updateStatus(id, "VOID")
            return Result.Success(Unit)
        }
        if (preferencesRepository?.getPreference(SettledInflowMarker.key(id)) == "1") {
            return Result.Error(IllegalStateException(SettledInflowMarker.BLOCKED_MESSAGE))
        }
        val request = com.financeautomation.domain.model.VoidInflowRequest(inflowId = id, timestamp = inflow.timestamp)
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
        syncMutex.withLock {
            try {
                val tDao = resolveTransactionDao()
                val qDao = resolveSyncQueueDao()
                val existing = tDao.getById(id)
                if (existing == null) {
                    val iDao = resolveInflowDao()
                    if (iDao?.getById(id) != null) {
                        return@withLock voidInflowLocked(id, iDao)
                    }
                    // Payables and repayments have no local row: they exist only as a queue item until delivered
                    val queued = qDao.getAllQueueFlow().first().filter { it.transactionId == id }
                    val isLoggedEntry = id.startsWith("pay_") || id.startsWith("rep_")
                    if (isLoggedEntry && queued.isNotEmpty()) {
                        qDao.deleteByTransactionId(id)
                        return@withLock Result.Success(Unit)
                    }
                    if (isLoggedEntry) {
                        return@withLock Result.Error(
                            IllegalStateException("This entry is already recorded on the sheet, so it can no longer be undone here")
                        )
                    }
                    return@withLock Result.Error(IllegalArgumentException("Transaction not found: $id"))
                }

                val pendingInsert = qDao.getAllPending().any {
                    it.transactionId == id && it.operationType == SyncQueueEntity.OP_INSERT
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
                    syncState = SyncState.SYNCED.name
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
        is java.io.IOException -> {
            throwable !is com.financeautomation.data.remote.SheetNotFoundException &&
            throwable !is com.financeautomation.data.remote.MalformedResponseException &&
            throwable !is com.financeautomation.data.remote.AuthExpiredException
        }
        else -> false
    }

    private suspend fun markTransportFailure(item: SyncQueueEntity, txn: Transaction, throwable: Throwable? = null) {
        // The transaction keeps its review state: a retryable failure is not a reason to ask the user again
        handleItemTransportFailure(item, throwable)
    }

    /** Called after every sync attempt, successful or not, for example to refresh the home screen widget. */
    var onSyncCompleted: (() -> Unit)? = null

    override suspend fun syncPendingTransactions(): Result<Int> {
        try {
            return runSyncPendingTransactions()
        } finally {
            try {
                onSyncCompleted?.invoke()
            } catch (_: Exception) {
                // A widget refresh must never turn a sync result into a failure
            }
        }
    }

    private suspend fun runSyncPendingTransactions(): Result<Int> = withContext(ioDispatcher) {
        syncMutex.withLock {
            try {
                if (googleSheetsClient == null || preferencesRepository == null) {
                    return@withLock Result.Success(0)
                }

            val transactionDao = resolveTransactionDao()
            val syncQueueDao = resolveSyncQueueDao()

            val pendingItems = syncQueueDao.getPendingQueueFifo(maxRetries = MAX_SYNC_RETRIES)
            if (pendingItems.isEmpty()) {
                reconcileWithSheet()
                return@withLock Result.Success(0)
            }

            val accounts = accountRepository?.getAllAccounts(includeArchived = false)?.map { it.name } ?: emptyList()
            val categories = categoryRepository?.getAllCategories(includeArchived = false)?.map { it.name } ?: emptyList()
            val knownTabsBySpreadsheet = mutableMapOf<String, MutableSet<String>>()

            var syncedCount = 0
            val st = RunState()
            val failedIds = st.failedIds

            for (item in pendingItems) {
                // Items that touch a transaction whose earlier item just failed wait for the next run to keep their order
                if (item.transactionId in failedIds) continue
                if (item.operationType == SyncQueueEntity.OP_CONFIG_PUSH) {
                    val configRes = syncConfigUseCase?.push()
                    if (configRes is Result.Success) {
                        syncQueueDao.deleteById(item.id)
                        syncedCount++
                    } else if (configRes is Result.Error) {
                        val err = configRes.exception
                        if (isTransportOrServerError(err)) {
                            val newRetries = item.retryCount + 1
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
                    item.operationType == SyncQueueEntity.OP_YEAR_CLOSE
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
                            googleSheetsClient.ensureMonthTabExists(spreadsheetId, opTabName, accounts, categories)
                            knownTabs.add(opTabName)
                        }
                    }

                    val executor = accountingSyncExecutor
                    val opResult = when (item.operationType) {
                        SyncQueueEntity.OP_LOG_CREDIT -> executor.executeLogCredit(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_VOID_INFLOW -> executor.executeVoidInflow(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_LOG_PAYABLE -> executor.executeLogPayable(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_LOG_REPAYMENT -> executor.executeLogRepayment(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_CARRY_FORWARD -> executor.executeCarryForward(item, spreadsheetId)
                        SyncQueueEntity.OP_YEAR_CLOSE -> executor.executeYearClose(item, spreadsheetId)
                        else -> Result.Success(Unit)
                    }

                    if (opResult is Result.Success) {
                        if (item.operationType == SyncQueueEntity.OP_LOG_CREDIT) {
                            rememberIfCreditSettledDebts(item.id, item.transactionId)
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
                            // Check if row with this UUID already exists in column AC
                            val uuidCheck = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, "AC", txn.id)
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

                            // Check for orphan row (same date, time, amount with blank AC from interrupted prior run)
                            val orphanCheck = googleSheetsClient.findOrphanDebitRow(
                                spreadsheetId, tabName, txn.dateSerial, txn.timeSerial, txn.totalAmount,
                                account = txn.account, description = txn.merchant
                            )
                            if (orphanCheck is Result.Success && orphanCheck.data != null) {
                                val orphanRow = orphanCheck.data
                                val writeUuidRes = googleSheetsClient.updateCell(spreadsheetId, tabName, "AC$orphanRow", txn.id)
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
                                uuid = txn.id
                            )

                            // Store plan before writing anything to guarantee idempotent retries
                            val planJson = AccountingPlanJsonAdapter.toJson(plan)
                            syncQueueDao.updatePlanJson(item.id, planJson)
                        }

                        val applyRes = applyPlan(spreadsheetId, plan)
                        if (applyRes is Result.Success) {
                            val rowIndex = applyRes.data ?: txn.sheetRowIndex
                            transactionDao.updateSyncState(txn.id, SyncState.SYNCED.name, rowIndex)
                            syncQueueDao.delete(item)
                            syncedCount++
                        } else {
                            val err = (applyRes as Result.Error).exception
                            if (failItem(item, txn, err, st)) break else continue
                        }
                    }
                    SyncQueueEntity.OP_UPDATE -> {
                        val cachedRow = txn.sheetRowIndex
                        var targetRow: Int? = null

                        if (cachedRow != null && cachedRow >= 4) {
                            val cellUuidRes = googleSheetsClient.getCellString(spreadsheetId, tabName, "AC$cachedRow")
                            if (cellUuidRes is Result.Success && cellUuidRes.data.equals(txn.id, ignoreCase = true)) {
                                targetRow = cachedRow
                            }
                        }

                        if (targetRow == null) {
                            val scanRes = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, "AC", txn.id)
                            if (scanRes is Result.Success && scanRes.data != null) {
                                targetRow = scanRes.data
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
                        val updateResult = googleSheetsClient.updateRow(spreadsheetId, tabName, targetRow, payload)
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
                        val plan: AccountingPlan
                        if (item.planJson != null) {
                            plan = AccountingPlanJsonAdapter.fromJson(item.planJson)
                        } else {
                            val cachedRow = txn.sheetRowIndex
                            var targetRow: Int? = null

                            if (cachedRow != null && cachedRow >= 4) {
                                val cellUuidRes = googleSheetsClient.getCellString(spreadsheetId, tabName, "AC$cachedRow")
                                if (cellUuidRes is Result.Success && cellUuidRes.data.equals(txn.id, ignoreCase = true)) {
                                    targetRow = cachedRow
                                }
                            }

                            if (targetRow == null) {
                                val scanRes = googleSheetsClient.findRowByUuid(spreadsheetId, tabName, "AC", txn.id)
                                if (scanRes is Result.Success && scanRes.data != null) {
                                    targetRow = scanRes.data
                                }
                            }

                            if (targetRow == null) {
                                markPermanentFailure(
                                    item, com.financeautomation.data.remote.SheetNotFoundException("", "Sheet changed: the row was not found"),
                                    "Sheet changed: the row was not found"
                                )
                                continue
                            }

                            val debit = LedgerDebit(
                                tab = tabName,
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

            if (st.authExpired) {
                Result.Error(com.financeautomation.data.remote.AuthExpiredException("Sign-in expired; the queue is paused until you reconnect"))
            } else if (st.transportFailure) {
                Result.Error(com.financeautomation.data.remote.GoogleSheetsException("Transport failure occurred during synchronization; scheduled for exponential backoff retry"))
            } else {
                reconcileWithSheet()
                Result.Success(syncedCount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}

    suspend fun reconcileWithSheet(targetSpreadsheetId: String? = null): Result<Int> = withContext(ioDispatcher) {
        try {
            val sheets = googleSheetsClient ?: return@withContext Result.Success(0)
            val tDao = resolveTransactionDao()
            val pDao = resolvePayableDao()

            val spreadsheetId = targetSpreadsheetId ?: run {
                val currentYear = java.time.ZonedDateTime.now(sheetTimeZone).year
                when (val res = workbookResolver?.resolveForYear(currentYear)) {
                    is Result.Success -> res.data
                    else -> preferencesRepository?.getSpreadsheetIdSync()
                }
            } ?: return@withContext Result.Success(0)

            val snapRes = sheets.readLedgerSnapshot(spreadsheetId)
            if (snapRes is Result.Error) {
                return@withContext Result.Error(snapRes.exception)
            }
            val snapshot = (snapRes as Result.Success).data
            var reconciledCount = 0

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
                    for (local in localPayables) {
                        if (!remoteUuids.contains(local.uuid)) {
                            pDao.deleteByUuid(local.uuid)
                        }
                    }
                    if (remotePayables.isNotEmpty()) {
                        pDao.upsertAll(remotePayables)
                    }
                }
            }

            Result.Success(reconciledCount)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            com.financeautomation.core.utils.Diagnostics.record(
                "TransactionRepositoryImpl",
                "reconcileWithSheet failed: ${e.message}"
            )
            Result.Error(e)
        }
    }

    private suspend fun applyPlan(
        spreadsheetId: String,
        plan: AccountingPlan
    ): Result<Int?> {
        val sheets = googleSheetsClient ?: return Result.Error(IllegalStateException("GoogleSheetsClient unavailable"))
        var lastAppendedRow: Int? = null
        var debitRow: Int? = null

        // Step 1: Appends first (with UUID in second step)
        for (append in plan.appends) {
            val colUuid = when (append.table) {
                TableType.DEBIT -> "AC"
                TableType.INFLOW -> "AD"
                TableType.PAYABLE -> "AE"
            }
            val existingCheck = sheets.findRowByUuid(spreadsheetId, append.tab, colUuid, append.uuid)
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
        if (isTransportOrServerError(err)) {
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
        resolveSyncQueueDao().setStatusError(item.id, SyncQueueEntity.STATUS_FAILED, 999, kind, detail.take(300))
    }

    private fun isRunWideFailure(err: Throwable): Boolean = when (err) {
        is java.net.SocketTimeoutException,
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException -> true
        is com.financeautomation.data.remote.GoogleSheetsApiException -> err.isRunWideBackoff()
        else -> false
    }

    private suspend fun handleItemTransportFailure(item: SyncQueueEntity, throwable: Throwable? = null) {
        val qDao = resolveSyncQueueDao()
        val newRetries = item.retryCount + 1
        val kind = SyncErrorKind.fromThrowable(throwable).name
        val detail = throwable?.message ?: throwable?.javaClass?.simpleName ?: "Transport failure"
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
        const val MAX_SYNC_RETRIES: Int = 8
        const val AMOUNT_MESSAGE: String = "Enter an amount greater than zero"
    }
}
