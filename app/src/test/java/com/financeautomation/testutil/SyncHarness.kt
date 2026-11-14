package com.financeautomation.testutil

import com.financeautomation.core.model.ReviewState
import com.financeautomation.core.model.SyncState
import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy

/**
 * A real [TransactionRepositoryImpl] over in-memory tables and a [ScriptedSheetsServer]. Nothing here accepts every
 * call: the server can fail a chosen request, and the tables behave like the Room queries.
 */
class SyncHarness(tabs: List<String> = listOf(ScriptedSheetsServer.currentTab())) {
    val tDao = InMemoryTransactionDao()
    val qDao = InMemoryQueueDao()
    val iDao = InMemoryInflowDao()
    val lDao = InMemoryLineageDao()
    /** Test clock shared by the repository and the read gate; move it to test intervals and cooldowns. */
    var now = System.currentTimeMillis()
    val gate = com.financeautomation.data.remote.SheetsReadGate { now }
    /** Drive modified time returned to the repository; null means the provider is not installed. */
    var modifiedTime: String? = null
    val server = ScriptedSheetsServer(tabs, gate)
    val sheets = server.client()
    var syncRequests = 0
    val preferences = mutableMapOf<String, String?>()
    var googleEmail: String? = "me@example.com"

    @Suppress("UNCHECKED_CAST")
    val prefs: PreferencesRepository = Proxy.newProxyInstance(
        PreferencesRepository::class.java.classLoader,
        arrayOf(PreferencesRepository::class.java)
    ) { _, method, args ->
        when {
            method.name == "getGoogleAccountEmailSync" -> googleEmail
            method.name == "setGoogleAccountEmail" -> { googleEmail = args!![0] as String?; null }
            method.name == "getPreference" -> preferences[args!![0] as String]
            method.name == "setPreference" -> { preferences[args!![0] as String] = args[1] as String?; null }
            method.name == "getSpreadsheetIdSync" || method.name == "getSpreadsheetIdForYearSync" -> "test_sheet_id"
            Flow::class.java.isAssignableFrom(method.returnType) -> flowOf<Any?>(null)
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == java.lang.Integer.TYPE -> 0
            else -> null
        }
    } as PreferencesRepository

    val repo = TransactionRepositoryImpl(
        transactionDao = tDao,
        syncQueueDao = qDao,
        inflowDao = iDao,
        lineageDao = lDao,
        googleSheetsClient = sheets,
        preferencesRepository = prefs,
        accountingSyncExecutor = createTestAccountingSyncExecutor(
            sheetsClient = sheets, syncQueueDao = qDao, lineageDao = lDao, transactionDao = tDao
        ),
        onTriggerSync = { syncRequests++ },
        ioDispatcher = Dispatchers.Unconfined,
        readGate = gate,
        modifiedTimeProvider = { _ -> modifiedTime?.let { com.financeautomation.core.model.Result.Success<String?>(it) } ?: com.financeautomation.core.model.Result.Success<String?>(null) },
        clock = { now }
    )

    fun expense(id: String, merchant: String = "Tea", amount: Double = 40.0, ageMillis: Long = 60_000L) = Transaction(
        id = id,
        timestamp = System.currentTimeMillis() - ageMillis,
        dateSerial = 46000,
        timeSerial = 0.5,
        account = "SBI",
        merchant = merchant,
        tripTag = "",
        category = "Food & Drink",
        totalAmount = amount,
        companions = "Personal",
        myShare = amount,
        owedToMe = 0.0,
        status = TransactionStatus.PERSONAL,
        sourcePackage = "manual",
        reviewState = ReviewState.CONFIRMED,
        syncState = SyncState.PENDING,
        sheetRowIndex = null,
        isPossibleDuplicate = false
    )

    companion object {
        /** A Google style error body, as the Sheets API sends it. */
        fun errorBody(code: Int, reason: String, message: String = "error"): String =
            """{"error":{"code":$code,"message":"$message","errors":[{"reason":"$reason","message":"$message"}],"status":"X"}}"""
    }
}
