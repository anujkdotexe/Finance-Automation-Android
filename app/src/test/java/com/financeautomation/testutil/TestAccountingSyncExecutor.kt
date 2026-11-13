package com.financeautomation.testutil

import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import okhttp3.OkHttpClient

/**
 * Creates an explicit [AccountingSyncExecutor] for unit tests, ensuring no mock fallbacks exist
 * in production code.
 */
fun createTestAccountingSyncExecutor(
    sheetsClient: GoogleSheetsClient? = null,
    driveClient: GoogleDriveClient? = null,
    syncQueueDao: SyncQueueDao? = null,
    lineageDao: LineageDao? = null,
    transactionDao: TransactionDao? = null
): AccountingSyncExecutor {
    val tokenProvider = object : TokenProvider {
        override fun getAccessToken(): String = "test_token"
        override fun refreshAccessToken(): String = "test_token"
    }
    val okHttpClient = OkHttpClient()
    val sheets = sheetsClient ?: GoogleSheetsClient(okHttpClient, tokenProvider)
    val drive = driveClient ?: GoogleDriveClient(okHttpClient, tokenProvider)
    return AccountingSyncExecutor(
        googleSheetsClient = sheets,
        googleDriveClient = drive,
        syncQueueDao = syncQueueDao,
        lineageDao = lineageDao,
        transactionDao = transactionDao
    )
}
