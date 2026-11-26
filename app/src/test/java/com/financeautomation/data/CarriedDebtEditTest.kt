package com.financeautomation.data

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.model.Result
import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.ThemeMode
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

class CarriedDebtEditTest {

    private class FakeTransactionDao : TransactionDao {
        val transactions = ConcurrentHashMap<String, TransactionEntity>()

        override fun getTransactionsFlow(): Flow<List<TransactionEntity>> = flowOf(transactions.values.toList())
        override fun getNeedsReviewFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getConfirmedUnsyncedFlow(): Flow<List<TransactionEntity>> = emptyFlow()
        override fun getAccountTransactionCounts(from: Long, to: Long): Flow<List<com.financeautomation.data.local.dao.AccountTransactionCount>> = emptyFlow()
        override suspend fun getById(id: String): TransactionEntity? = transactions[id]
        override suspend fun getBySheetRowIndex(rowIndex: Int): TransactionEntity? =
            transactions.values.firstOrNull { it.sheetRowIndex == rowIndex }
        override suspend fun getAllBySheetRowIndex(rowIndex: Int): List<TransactionEntity> =
            transactions.values.filter { it.sheetRowIndex == rowIndex }
        override suspend fun getRecentSimilar(minTimestamp: Long, maxTimestamp: Long, minAmount: Double, maxAmount: Double): List<TransactionEntity> =
            transactions.values.filter { it.timestamp in minTimestamp..maxTimestamp && it.totalAmount in minAmount..maxAmount }.sortedByDescending { it.timestamp }
        override suspend fun insert(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun insertAll(transactions: List<TransactionEntity>) { transactions.forEach { insert(it) } }
        override suspend fun update(transaction: TransactionEntity) { transactions[transaction.id] = transaction }
        override suspend fun delete(transaction: TransactionEntity) { transactions.remove(transaction.id) }
        override suspend fun deleteById(id: String) { transactions.remove(id) }
        override suspend fun voidTransaction(id: String, voidTitle: String) {
            transactions[id]?.let {
                transactions[id] = it.copy(
                    totalAmount = 0.0,
                    myShare = 0.0,
                    owedToMe = 0.0,
                    status = "Void",
                    syncState = SyncState.VOID.name,
                    reviewState = ReviewState.CONFIRMED.name,
                    merchant = voidTitle
                )
            }
        }
        override suspend fun updateSyncState(id: String, syncState: String, sheetRowIndex: Int?) {
            transactions[id]?.let {
                transactions[id] = it.copy(syncState = syncState, sheetRowIndex = sheetRowIndex ?: it.sheetRowIndex)
            }
        }
        override suspend fun updateReviewState(id: String, reviewState: String, category: String?) {
            transactions[id]?.let { transactions[id] = it.copy(reviewState = reviewState, category = category ?: it.category) }
        }
        override suspend fun updateAccountName(oldName: String, newName: String): Int = 0
        override suspend fun updateCategoryName(oldName: String, newName: String): Int = 0
        override suspend fun updateCompanionName(oldName: String, newName: String): Int = 0
        override suspend fun getAllForYear(startMillis: Long, endMillis: Long): List<TransactionEntity> =
            transactions.values.filter {
                it.timestamp in startMillis until endMillis &&
                    !it.status.equals("Void", ignoreCase = true) &&
                    it.reviewState != "NEEDS_REVIEW"
            }.sortedBy { it.timestamp }
        override suspend fun resetSyncStateForRequeue(id: String) {
            transactions[id]?.let { transactions[id] = it.copy(syncState = SyncState.PENDING.name, sheetRowIndex = null) }
        }
    }

    private class FakeSyncQueueDao : SyncQueueDao {
        val queue = ConcurrentHashMap<Long, SyncQueueEntity>()
        private val idGen = AtomicInteger(1)

        override fun getPendingQueueFlow(): Flow<List<SyncQueueEntity>> = emptyFlow()
        override fun getAllQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.toList())
        override fun getFailedQueueFlow(): Flow<List<SyncQueueEntity>> = flowOf(queue.values.filter { it.status == SyncQueueEntity.STATUS_FAILED || it.retryCount >= 8 })
        override suspend fun insert(item: SyncQueueEntity): Long {
            val id = if (item.id == 0L) idGen.getAndIncrement().toLong() else item.id
            val stored = item.copy(id = id)
            queue[id] = stored
            return id
        }
        override suspend fun update(item: SyncQueueEntity) { queue[item.id] = item }
        override suspend fun delete(item: SyncQueueEntity) { queue.remove(item.id) }
        override suspend fun deleteById(id: Long) { queue.remove(id) }
        override suspend fun getById(id: Long): SyncQueueEntity? = queue[id]
        override suspend fun deleteByTransactionId(transactionId: String) {
            queue.values.filter { it.transactionId == transactionId }.forEach { queue.remove(it.id) }
        }
        override suspend fun getNextPending(maxRetries: Int): SyncQueueEntity? =
            queue.values.firstOrNull { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }
        override suspend fun getAllPending(maxRetries: Int): List<SyncQueueEntity> =
            queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }
        override suspend fun getPendingQueueFifo(maxRetries: Int): List<SyncQueueEntity> =
            queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING || (it.status == SyncQueueEntity.STATUS_FAILED && it.retryCount < maxRetries) }.sortedBy { it.createdAt }
        override suspend fun updateStatus(id: Long, status: String, retryIncrement: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement) }
        }
        override suspend fun setStatusAndRetryCount(id: Long, status: String, retryCount: Int) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount) }
        }
        override suspend fun setStatusError(id: Long, status: String, retryCount: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = retryCount, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun updateStatusError(id: Long, status: String, retryIncrement: Int, kind: String?, detail: String?) {
            queue[id]?.let { queue[id] = it.copy(status = status, retryCount = it.retryCount + retryIncrement, lastErrorKind = kind, lastErrorDetail = detail) }
        }
        override suspend fun resetQueueItem(id: Long) {
            queue[id]?.let { queue[id] = it.copy(status = SyncQueueEntity.STATUS_PENDING, retryCount = 0, lastErrorKind = null, lastErrorDetail = null) }
        }
        override suspend fun recoverRetryableFailedItems(): Int = 0
        override suspend fun countPendingOperations(opType: String): Int =
            queue.values.count { it.operationType == opType && it.status == SyncQueueEntity.STATUS_PENDING }
        override suspend fun updatePlanJson(id: Long, planJson: String?) {
            queue[id]?.let { queue[id] = it.copy(planJson = planJson) }
        }
        override suspend fun updateTransactionId(oldTransactionId: String, newTransactionId: String) {
            queue.values.filter { it.transactionId == oldTransactionId }.forEach {
                queue[it.id] = it.copy(transactionId = newTransactionId)
            }
        }
        override suspend fun updatePayloadJsonId(oldId: String, newId: String) {
            queue.values.filter { it.payloadJson.contains(oldId) }.forEach {
                queue[it.id] = it.copy(payloadJson = it.payloadJson.replace(oldId, newId))
            }
        }
        override suspend fun updatePlanJsonId(oldId: String, newId: String) {
            queue.values.filter { it.planJson != null && it.planJson!!.contains(oldId) }.forEach {
                queue[it.id] = it.copy(planJson = it.planJson!!.replace(oldId, newId))
            }
        }
        override suspend fun getActiveTransactionIds(): List<String> =
            queue.values.filter { it.status == SyncQueueEntity.STATUS_PENDING || it.status == SyncQueueEntity.STATUS_IN_PROGRESS }
                .map { it.transactionId }
        override suspend fun deletePendingForTransactions(transactionIds: List<String>) {
            val idSet = transactionIds.toSet()
            queue.values.filter {
                it.transactionId in idSet &&
                it.status == SyncQueueEntity.STATUS_PENDING &&
                (it.operationType == SyncQueueEntity.OP_INSERT || it.operationType == SyncQueueEntity.OP_UPDATE || it.operationType == SyncQueueEntity.OP_VOID)
            }.forEach { queue.remove(it.id) }
        }
    }

    private class FakePreferencesRepository(private val spreadsheetId: String = "test_sheet_id") : PreferencesRepository {
        override fun getSpreadsheetId(): Flow<String?> = flowOf(spreadsheetId)
        override suspend fun getSpreadsheetIdSync(): String? = spreadsheetId
        override suspend fun setSpreadsheetId(id: String) {}
        override fun getSpreadsheetIdForYear(year: Int): Flow<String?> = flowOf(spreadsheetId)
        override suspend fun getSpreadsheetIdForYearSync(year: Int): String? = spreadsheetId
        override suspend fun setSpreadsheetIdForYear(year: Int, id: String) {}
        override fun getDefaultAccount(): Flow<String> = flowOf("SBI")
        override suspend fun getDefaultAccountSync(): String = "SBI"
        override suspend fun setDefaultAccount(account: String) {}
        override fun getActiveTrip(): Flow<String?> = flowOf(null)
        override suspend fun setActiveTrip(trip: String?) {}
        override fun getGoogleAccountEmail(): Flow<String?> = flowOf(null)
        override suspend fun getGoogleAccountEmailSync(): String? = "test@example.com"
        override suspend fun setGoogleAccountEmail(email: String?) {}
        override fun getSettleDelaySeconds(): Flow<Int> = flowOf(5)
        override suspend fun getSettleDelaySecondsSync(): Int = 5
        override suspend fun setSettleDelaySeconds(seconds: Int) {}
        override suspend fun getOrCreateDeviceId(): String = "device_123"
        override suspend fun getDeviceIdSync(): String? = "device_123"
        override fun getDeveloperMode(): Flow<Boolean> = flowOf(false)
        override suspend fun getDeveloperModeSync(): Boolean = false
        override suspend fun setDeveloperMode(enabled: Boolean) {}
        override fun getThemeMode(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun getThemeModeSync(): ThemeMode = ThemeMode.SYSTEM
        override suspend fun setThemeMode(mode: ThemeMode) {}
        override suspend fun getPreference(key: String): String? = null
        override suspend fun setPreference(key: String, value: String?, isDirty: Boolean) {}
        override suspend fun deletePreference(key: String) {}
    }

    private class FakeLineageDao : LineageDao {
        val records = ConcurrentHashMap<String, LineageEntity>()
        override suspend fun insert(entity: LineageEntity) { records[entity.uuid] = entity }
        override suspend fun insertAll(entities: List<LineageEntity>) { entities.forEach { insert(it) } }
        override suspend fun getByUuid(uuid: String): LineageEntity? = records[uuid]
        override suspend fun getByCarriedFrom(carriedFromUuid: String): LineageEntity? =
            records.values.firstOrNull { it.carriedFromUuid == carriedFromUuid }
        override suspend fun getAll(): List<LineageEntity> = records.values.toList()
        override suspend fun updateUuid(oldUuid: String, newUuid: String) {}
        override suspend fun updateCarriedFromUuid(oldUuid: String, newUuid: String) {}
        override suspend fun updateRootUuid(oldUuid: String, newUuid: String) {}
    }

    private class MockSheets {
        val recordedBodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        var sheetRowResponse: String? = null
        var shouldFailRead: Boolean = false

        val tokenProvider = object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        }

        fun createClient(): GoogleSheetsClient {
            val interceptor = Interceptor { chain ->
                val request = chain.request()
                val rawUrl = request.url.toString()
                val url = java.net.URLDecoder.decode(rawUrl, "UTF-8")
                val method = request.method

                val bodyText = request.body?.let { b ->
                    val buf = okio.Buffer()
                    b.writeTo(buf)
                    buf.readUtf8()
                }.orEmpty()
                if (bodyText.isNotEmpty()) recordedBodies.add(bodyText)

                if (method == "GET" && shouldFailRead && (url.contains("A5:L5") || url.contains("A5%3AL5"))) {
                    return@Interceptor Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Service Unavailable")
                        .body("Service Unavailable".toResponseBody(JSON_MEDIA_TYPE))
                        .build()
                }

                val responseBody = when {
                    // Header check for L3
                    method == "GET" && url.contains("L3") ->
                        """{"range":"'Nov26'!L3:L3","majorDimension":"ROWS","values":[["Note"]]}"""
                    // getCellString for UUID column
                    method == "GET" && (url.contains("!AD5") || url.contains("!AC5") || url.contains("AD5") || url.contains("AC5")) ->
                        """{"range":"'Nov26'!AD5","majorDimension":"ROWS","values":[["txn_carried_1"]]}"""
                    // findDebitRow: AD4:AD or AC4:AC
                    method == "GET" && (url.contains("AD4:AD") || url.contains("AC4:AC")) ->
                        """{"range":"'Nov26'!AD4:AD","majorDimension":"ROWS","values":[["other"],["txn_carried_1"]]}"""
                    // Read existing sheet row A5:L5
                    method == "GET" && url.contains("/values/") && url.contains("A5:L5") ->
                        sheetRowResponse ?: """{"range":"'Nov26'!A5:L5","majorDimension":"ROWS","values":[["46358","0.5","SBI","Dinner","","Food","1000","Riya","500","500","Carried Fwd","Initial Note"]]}"""
                    // Tabs metadata
                    method == "GET" && !url.contains("/values/") ->
                        """{"spreadsheetId":"test_sheet_id","sheets":[{"properties":{"sheetId":0,"title":"Nov26","gridProperties":{"rowCount":1000,"columnCount":35}}}]}"""
                    // updateRow (PUT)
                    method == "PUT" && url.contains("/values/") ->
                        """{"updatedCells":12}"""
                    else ->
                        """{"range":"'Nov26'!A1:L1","majorDimension":"ROWS","values":[]}"""
                }

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody(JSON_MEDIA_TYPE))
                    .build()
            }
            val okHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
            return GoogleSheetsClient(okHttp, tokenProvider)
        }
    }

    private fun createRepository(
        tDao: FakeTransactionDao,
        qDao: FakeSyncQueueDao,
        sheets: GoogleSheetsClient
    ): TransactionRepositoryImpl {
        val driveClient = GoogleDriveClient(OkHttpClient(), object : TokenProvider {
            override fun getAccessToken(): String = "test_token"
            override fun refreshAccessToken(): String = "test_token"
        })
        val executor = AccountingSyncExecutor(
            googleSheetsClient = sheets,
            googleDriveClient = driveClient,
            syncQueueDao = qDao,
            lineageDao = FakeLineageDao()
        )
        return TransactionRepositoryImpl(
            transactionDao = tDao,
            syncQueueDao = qDao,
            googleSheetsClient = sheets,
            preferencesRepository = FakePreferencesRepository(),
            accountingSyncExecutor = executor,
            calculateSplitUseCase = CalculateSplitUseCase()
        )
    }

    @Test
    fun testCarriedRowEditRefusesAmountOrSplitChange() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val mock = MockSheets()
        mock.sheetRowResponse = """{"range":"'Nov26'!A5:L5","majorDimension":"ROWS","values":[["46358","0.5","SBI","Dinner","","Food","1000","Riya","500","500","Carried Fwd","Old Note"]]}"""
        val sheets = mock.createClient()
        val repo = createRepository(tDao, qDao, sheets)

        val txnId = "txn_carried_1"
        val initialTxn = Transaction(
            id = txnId,
            timestamp = 1794387699000L,
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food",
            totalAmount = 1000.0,
            companions = "Riya",
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING,
            note = "Old Note",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.SYNCED,
            sheetRowIndex = 5,
            isPossibleDuplicate = false
        )
        tDao.insert(TransactionEntity.fromDomain(initialTxn))

        // User edits amount to 1200
        val updatedTxn = initialTxn.copy(totalAmount = 1200.0)
        tDao.update(TransactionEntity.fromDomain(updatedTxn))

        qDao.insert(
            SyncQueueEntity(
                transactionId = txnId,
                operationType = SyncQueueEntity.OP_UPDATE,
                payloadJson = txnId
            )
        )

        repo.syncPendingTransactions()

        val queueItem = qDao.getAllQueueFlow().first().first()
        assertEquals(SyncQueueEntity.STATUS_FAILED, queueItem.status)
        assertTrue(queueItem.lastErrorDetail?.contains("carried forward") == true)

        val putBody = mock.recordedBodies.find { it.contains("1200") }
        assertNull("Sheet update with changed amount must be refused", putBody)

        val roomTxn = tDao.getById(txnId)
        assertNotNull(roomTxn)
        assertEquals(TransactionStatus.CARRIED_FWD.value, roomTxn!!.status)
        assertEquals(500.0, roomTxn.owedToMe, 0.01)
    }

    @Test
    fun testCarriedRowEditAllowsCategoryOrNoteChangeWithoutTouchingMoneyOrStatus() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val mock = MockSheets()
        mock.sheetRowResponse = """{"range":"'Nov26'!A5:L5","majorDimension":"ROWS","values":[["46358","0.5","SBI","Dinner","","Food","1000","Riya","500","500","Carried Fwd","Old Note"]]}"""
        val sheets = mock.createClient()
        val repo = createRepository(tDao, qDao, sheets)

        val txnId = "txn_carried_1"
        val initialTxn = Transaction(
            id = txnId,
            timestamp = 1794387699000L,
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food",
            totalAmount = 1000.0,
            companions = "Riya",
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING,
            note = "Old Note",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.SYNCED,
            sheetRowIndex = 5,
            isPossibleDuplicate = false
        )
        tDao.insert(TransactionEntity.fromDomain(initialTxn))

        val updatedTxn = initialTxn.copy(category = "Travel", note = "Updated Note")
        tDao.update(TransactionEntity.fromDomain(updatedTxn))

        qDao.insert(
            SyncQueueEntity(
                transactionId = txnId,
                operationType = SyncQueueEntity.OP_UPDATE,
                payloadJson = txnId
            )
        )

        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)

        val updateBody = mock.recordedBodies.find { it.contains("Travel") }
        assertNotNull("Update payload must be written", updateBody)
        assertTrue(updateBody!!.contains("Updated Note"))
        assertTrue(updateBody.contains("Carried Fwd"))

        val roomTxn = tDao.getById(txnId)
        assertNotNull(roomTxn)
        assertEquals(TransactionStatus.CARRIED_FWD.value, roomTxn!!.status)
        assertEquals("Travel", roomTxn.category)
        assertEquals("Updated Note", roomTxn.note)
    }

    @Test
    fun testSheetRepaidRowNeverReopenedFromOlderLocalPending() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val mock = MockSheets()
        mock.sheetRowResponse = """{"range":"'Nov26'!A5:L5","majorDimension":"ROWS","values":[["46358","0.5","SBI","Dinner","","Food","1000","Riya","500","0","Settled","Old Note"]]}"""
        val sheets = mock.createClient()
        val repo = createRepository(tDao, qDao, sheets)

        val txnId = "txn_carried_1"
        val initialTxn = Transaction(
            id = txnId,
            timestamp = 1794387699000L,
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food",
            totalAmount = 1000.0,
            companions = "Riya",
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING,
            note = "Old Note",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.SYNCED,
            sheetRowIndex = 5,
            isPossibleDuplicate = false
        )
        tDao.insert(TransactionEntity.fromDomain(initialTxn))

        val updatedTxn = initialTxn.copy(note = "Clean Note")
        tDao.update(TransactionEntity.fromDomain(updatedTxn))

        qDao.insert(
            SyncQueueEntity(
                transactionId = txnId,
                operationType = SyncQueueEntity.OP_UPDATE,
                payloadJson = txnId
            )
        )

        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)

        val updateBody = mock.recordedBodies.find { it.contains("Clean Note") }
        assertNotNull(updateBody)
        assertTrue(updateBody!!.contains("Settled"))
        org.junit.Assert.assertFalse("Must not write Pending back to sheet", updateBody.contains("\"Pending\""))

        val roomTxn = tDao.getById(txnId)
        assertNotNull(roomTxn)
        assertEquals(TransactionStatus.SETTLED.value, roomTxn!!.status)
        assertEquals(0.0, roomTxn.owedToMe, 0.01)
    }

    @Test
    fun testFailedSheetReadRetriesAndChangesNothing() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val mock = MockSheets()
        mock.shouldFailRead = true
        val sheets = mock.createClient()
        val repo = createRepository(tDao, qDao, sheets)

        val txnId = "txn_carried_1"
        val initialTxn = Transaction(
            id = txnId,
            timestamp = 1794387699000L,
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food",
            totalAmount = 1000.0,
            companions = "Riya",
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING,
            note = "Old Note",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.SYNCED,
            sheetRowIndex = 5,
            isPossibleDuplicate = false
        )
        tDao.insert(TransactionEntity.fromDomain(initialTxn))

        qDao.insert(
            SyncQueueEntity(
                transactionId = txnId,
                operationType = SyncQueueEntity.OP_UPDATE,
                payloadJson = txnId
            )
        )

        repo.syncPendingTransactions()

        assertNull(mock.recordedBodies.find { it.contains("update") })

        val queueItem = qDao.getAllQueueFlow().first().first()
        assertEquals(SyncQueueEntity.STATUS_PENDING, queueItem.status)
        assertEquals(1, queueItem.retryCount)
    }

    @Test
    fun testOrdinaryEditWithNothingMovedWorksNormally() = runBlocking {
        val tDao = FakeTransactionDao()
        val qDao = FakeSyncQueueDao()
        val mock = MockSheets()
        mock.sheetRowResponse = """{"range":"'Nov26'!A5:L5","majorDimension":"ROWS","values":[["46358","0.5","SBI","Dinner","","Food","1000","Riya","500","500","Pending","Old Note"]]}"""
        val sheets = mock.createClient()
        val repo = createRepository(tDao, qDao, sheets)

        val txnId = "txn_carried_1"
        val initialTxn = Transaction(
            id = txnId,
            timestamp = 1794387699000L,
            dateSerial = 46358,
            timeSerial = 0.5,
            account = "SBI",
            merchant = "Dinner",
            tripTag = "",
            category = "Food",
            totalAmount = 1000.0,
            companions = "Riya",
            myShare = 500.0,
            owedToMe = 500.0,
            status = TransactionStatus.PENDING,
            note = "Old Note",
            sourcePackage = "manual",
            reviewState = ReviewState.CONFIRMED,
            syncState = SyncState.SYNCED,
            sheetRowIndex = 5,
            isPossibleDuplicate = false
        )
        tDao.insert(TransactionEntity.fromDomain(initialTxn))

        val updatedTxn = initialTxn.copy(merchant = "Dinner Renamed", note = "New Note")
        tDao.update(TransactionEntity.fromDomain(updatedTxn))

        qDao.insert(
            SyncQueueEntity(
                transactionId = txnId,
                operationType = SyncQueueEntity.OP_UPDATE,
                payloadJson = txnId
            )
        )

        val syncRes = repo.syncPendingTransactions()
        assertTrue(syncRes is Result.Success)
        assertEquals(0, qDao.getAllQueueFlow().first().size)

        val updateBody = mock.recordedBodies.find { it.contains("Dinner Renamed") }
        assertNotNull(updateBody)
        assertTrue(updateBody!!.contains("New Note"))
    }
}
