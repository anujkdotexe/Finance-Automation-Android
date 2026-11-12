package com.financeautomation.data.repository

import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.utils.DateSerialUtils
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.FinanceDatabase
import androidx.room.withTransaction
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingPlanJsonAdapter
import com.financeautomation.data.remote.AccountingRequestJsonAdapter
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleSheetsClient
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
    var accountingSyncExecutor: AccountingSyncExecutor? = null,
    var onTriggerSync: (() -> Unit)? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : TransactionRepository {

    constructor(
        transactionDao: TransactionDao,
        syncQueueDao: SyncQueueDao,
        payableDao: PayableDao? = null,
        googleSheetsClient: GoogleSheetsClient? = null,
        preferencesRepository: PreferencesRepository? = null,
        workbookResolver: WorkbookResolver? = null,
        accountRepository: AccountRepository? = null,
        categoryRepository: CategoryRepository? = null,
        syncConfigUseCase: SyncConfigUseCase? = null,
        calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
        nettingUseCase: NettingUseCase = NettingUseCase(),
        voidTransactionUseCase: VoidTransactionUseCase = VoidTransactionUseCase(),
        accountingSyncExecutor: AccountingSyncExecutor? = null,
        onTriggerSync: (() -> Unit)? = null,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        transactionDao = transactionDao,
        syncQueueDao = syncQueueDao,
        transactionDaoProvider = null,
        syncQueueDaoProvider = null,
        payableDao = payableDao,
        payableDaoProvider = null,
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

    override suspend fun insertTransaction(transaction: Transaction): Result<Unit> = withContext(ioDispatcher) {
        try {
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val entity = TransactionEntity.fromDomain(transaction)
            runInDbTransaction {
                tDao.insert(entity)

                if (transaction.syncState == SyncState.PENDING) {
                    val queueItem = SyncQueueEntity(
                        transactionId = transaction.id,
                        operationType = SyncQueueEntity.OP_INSERT,
                        payloadJson = transaction.id
                    )
                    qDao.insert(queueItem)
                }
            }
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun updateTransaction(transaction: Transaction): Result<Unit> = withContext(ioDispatcher) {
        try {
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val entity = TransactionEntity.fromDomain(transaction)
            runInDbTransaction {
                tDao.update(entity)

                val queueItem = SyncQueueEntity(
                    transactionId = transaction.id,
                    operationType = SyncQueueEntity.OP_UPDATE,
                    payloadJson = transaction.id
                )
                qDao.insert(queueItem)
            }
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
            resolveSyncQueueDao().insert(queueItem)
            onTriggerSync?.invoke()
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
            onTriggerSync?.invoke()
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
            onTriggerSync?.invoke()
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
            onTriggerSync?.invoke()
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
            onTriggerSync?.invoke()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    override suspend fun voidTransaction(id: String): Result<Unit> = withContext(ioDispatcher) {
        try {
            val tDao = resolveTransactionDao()
            val qDao = resolveSyncQueueDao()
            val existing = tDao.getById(id)
                ?: return@withContext Result.Error(IllegalArgumentException("Transaction not found: $id"))

            val voidTitle = if (existing.merchant.startsWith("[Void] ")) {
                existing.merchant
            } else {
                "[Void] ${existing.merchant}"
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
        is com.financeautomation.data.remote.GoogleSheetsApiException -> throwable.statusCode in 500..599
        is java.io.IOException -> {
            throwable !is com.financeautomation.data.remote.SheetNotFoundException &&
            throwable !is com.financeautomation.data.remote.MalformedResponseException &&
            throwable !is com.financeautomation.data.remote.AuthExpiredException
        }
        else -> false
    }

    private suspend fun markTransportFailure(item: SyncQueueEntity, txn: Transaction) {
        val tDao = resolveTransactionDao()
        val qDao = resolveSyncQueueDao()
        val newRetry = item.retryCount + 1
        if (newRetry >= MAX_SYNC_RETRIES) {
            qDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, newRetry)
            tDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
        } else {
            qDao.updateStatus(item.id, SyncQueueEntity.STATUS_FAILED, 1)
        }
    }

    override suspend fun syncPendingTransactions(): Result<Int> = withContext(ioDispatcher) {
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
            var hasTransportFailure = false

            for (item in pendingItems) {
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
                            hasTransportFailure = true
                            break
                        } else {
                            syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, MAX_SYNC_RETRIES)
                        }
                    }
                    continue
                }

                if (item.operationType == SyncQueueEntity.OP_LOG_CREDIT ||
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
                                if (isTransportOrServerError(err)) {
                                    handleItemTransportFailure(item)
                                    hasTransportFailure = true
                                    break
                                } else {
                                    syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                    continue
                                }
                            }
                            is Result.Loading -> {
                                hasTransportFailure = true
                                break
                            }
                        }
                    } else {
                        preferencesRepository.getSpreadsheetIdForYearSync(opYear)
                    }

                    if (spreadsheetId.isNullOrBlank()) {
                        handleItemTransportFailure(item)
                        hasTransportFailure = true
                        break
                    }

                    val opTabName = when (item.operationType) {
                        SyncQueueEntity.OP_LOG_CREDIT -> {
                            val req = AccountingRequestJsonAdapter.creditRequestFromJson(item.payloadJson)
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
                        SyncQueueEntity.OP_LOG_CREDIT -> executor?.executeLogCredit(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_LOG_PAYABLE -> executor?.executeLogPayable(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_LOG_REPAYMENT -> executor?.executeLogRepayment(item, spreadsheetId, opTabName)
                        SyncQueueEntity.OP_CARRY_FORWARD -> executor?.executeCarryForward(item, spreadsheetId)
                        SyncQueueEntity.OP_YEAR_CLOSE -> executor?.executeYearClose(item, spreadsheetId)
                        else -> Result.Success(Unit)
                    } ?: Result.Success(Unit)

                    if (opResult is Result.Success) {
                        syncQueueDao.deleteById(item.id)
                        syncedCount++
                    } else if (opResult is Result.Error) {
                        val err = opResult.exception
                        if (isTransportOrServerError(err)) {
                            handleItemTransportFailure(item)
                            hasTransportFailure = true
                            break
                        } else {
                            syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                        }
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
                            if (isTransportOrServerError(err)) {
                                markTransportFailure(item, txn)
                                hasTransportFailure = true
                                break
                            } else {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                continue
                            }
                        }
                        is Result.Loading -> {
                            hasTransportFailure = true
                            break
                        }
                    }
                } else {
                    preferencesRepository.getSpreadsheetIdForYearSync(year)
                }

                if (spreadsheetId.isNullOrBlank()) {
                    markTransportFailure(item, txn)
                    hasTransportFailure = true
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
                            if (isTransportOrServerError(err)) {
                                markTransportFailure(item, txn)
                                hasTransportFailure = true
                                break
                            } else {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                continue
                            }
                        }
                        is Result.Loading -> {
                            hasTransportFailure = true
                            break
                        }
                    }
                }

                if (!knownTabs.contains(tabName)) {
                    val tabCheckResult = googleSheetsClient.ensureMonthTabExists(spreadsheetId, tabName, accounts, categories)
                    if (tabCheckResult is Result.Error) {
                        val err = tabCheckResult.exception
                        if (isTransportOrServerError(err)) {
                            markTransportFailure(item, txn)
                            hasTransportFailure = true
                            break
                        } else {
                            syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                            transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                            continue
                        }
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
                        val cfRes = accountingSyncExecutor?.executeCarryForward(cfQueueItem, spreadsheetId)
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
                                if (isTransportOrServerError(err)) {
                                    markTransportFailure(item, txn)
                                    hasTransportFailure = true
                                    break
                                } else {
                                    syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                    transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                    continue
                                }
                            }

                            // Check for orphan row (same date, time, amount with blank AC from interrupted prior run)
                            val orphanCheck = googleSheetsClient.findOrphanDebitRow(
                                spreadsheetId, tabName, txn.dateSerial, txn.timeSerial, txn.totalAmount
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
                                    if (isTransportOrServerError(err)) {
                                        markTransportFailure(item, txn)
                                        hasTransportFailure = true
                                        break
                                    } else {
                                        syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                        transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                        continue
                                    }
                                }
                            } else if (orphanCheck is Result.Error) {
                                val err = orphanCheck.exception
                                if (isTransportOrServerError(err)) {
                                    markTransportFailure(item, txn)
                                    hasTransportFailure = true
                                    break
                                } else {
                                    syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                    transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                    continue
                                }
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
                                    if (isTransportOrServerError(err)) {
                                        markTransportFailure(item, txn)
                                        hasTransportFailure = true
                                        break
                                    } else {
                                        syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                        transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                        continue
                                    }
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
                            if (isTransportOrServerError(err)) {
                                markTransportFailure(item, txn)
                                hasTransportFailure = true
                                break
                            } else {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                continue
                            }
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
                            syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                            transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
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
                            if (isTransportOrServerError(err)) {
                                markTransportFailure(item, txn)
                                hasTransportFailure = true
                                break
                            } else {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                continue
                            }
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
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
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
                            if (isTransportOrServerError(err)) {
                                markTransportFailure(item, txn)
                                hasTransportFailure = true
                                break
                            } else {
                                syncQueueDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, 999)
                                transactionDao.updateReviewState(txn.id, ReviewState.NEEDS_REVIEW.name)
                                continue
                            }
                        }
                    }
                }
            }

            if (hasTransportFailure) {
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

            runInDbTransaction {
                for (month in snapshot.monthTabs) {
                    for (sheetDebit in month.debits) {
                        val uuid = sheetDebit.uuid
                        val localTxn = (if (!uuid.isNullOrBlank()) {
                            tDao.getById(uuid)
                        } else null) ?: tDao.getBySheetRowIndex(sheetDebit.rowNumber)

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

        // Step 1: Appends first (with UUID in second step)
        for (append in plan.appends) {
            val colUuid = when (append.table) {
                TableType.DEBIT -> "AC"
                TableType.INFLOW -> "AD"
                TableType.PAYABLE -> "AE"
            }
            val existingCheck = sheets.findRowByUuid(spreadsheetId, append.tab, colUuid, append.uuid)
            if (existingCheck is Result.Success && existingCheck.data != null) {
                lastAppendedRow = existingCheck.data
                continue
            } else if (existingCheck is Result.Error) {
                val err = existingCheck.exception
                if (isTransportOrServerError(err)) return Result.Error(err)
            }

            val appendRes = when (append.table) {
                TableType.DEBIT -> sheets.appendDebit(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
                TableType.INFLOW -> sheets.appendInflow(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
                TableType.PAYABLE -> sheets.appendPayable(spreadsheetId, append.tab, append.cells, uuid = append.uuid)
            }

            if (appendRes is Result.Success) {
                lastAppendedRow = appendRes.data
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

        // Step 3: Apply plan edits and settled references locally to Room database
        applyPlanToLocalDatabase(plan)

        return Result.Success(lastAppendedRow)
    }

    private suspend fun applyPlanToLocalDatabase(plan: AccountingPlan) {
        try {
            val tDao = resolveTransactionDao()
            val editsByRow = plan.edits.groupBy { it.row }
            val allTargetRows = (editsByRow.keys + plan.settled.map { it.row }).distinct()

            runInDbTransaction {
                for (rowNum in allTargetRows) {
                    val settledRef = plan.settled.firstOrNull { it.row == rowNum }
                    val existingTxn = (settledRef?.uuid?.let { tDao.getById(it) })
                        ?: tDao.getBySheetRowIndex(rowNum)

                    if (existingTxn != null) {
                        var updated: com.financeautomation.data.local.entity.TransactionEntity = existingTxn
                        val rowEdits = editsByRow[rowNum].orEmpty()
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
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.financeautomation.core.utils.Diagnostics.record(
                "TransactionRepositoryImpl",
                "applyPlanToLocalDatabase failed: ${e.message}"
            )
        }
    }

    private suspend fun handleItemTransportFailure(item: SyncQueueEntity) {
        val qDao = resolveSyncQueueDao()
        val newRetries = item.retryCount + 1
        if (newRetries >= MAX_SYNC_RETRIES) {
            qDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_FAILED, newRetries)
        } else {
            qDao.setStatusAndRetryCount(item.id, SyncQueueEntity.STATUS_PENDING, newRetries)
        }
    }

    private fun formatMonthTabName(timestamp: Long): String {
        val dateTime = Instant.ofEpochMilli(timestamp).atZone(sheetTimeZone)
        val formatter = DateTimeFormatter.ofPattern("MMMyy", Locale.US)
        return dateTime.format(formatter)
    }

    companion object {
        const val MAX_SYNC_RETRIES: Int = 8
    }
}
