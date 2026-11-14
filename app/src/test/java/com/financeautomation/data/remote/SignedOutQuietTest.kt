package com.financeautomation.data.remote

import com.financeautomation.core.model.Result
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.testutil.SyncHarness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An app that was signed out on purpose stays quiet: no reconnect notification, no requests, no damage to the queue. */
class SignedOutQuietTest {

    @Test
    fun noReconnectNotificationWhenSignedOutByChoice() {
        val signedOut = AuthResult.Failed(AuthExpiredException(GoogleAuthTokenProvider.NO_ACCOUNT_MESSAGE))
        assertFalse(GoogleAuthTokenProvider.shouldNotifyReconnect(signedOut))
    }

    @Test
    fun aRealConsentOrTokenProblemStillNotifies() {
        assertTrue(GoogleAuthTokenProvider.shouldNotifyReconnect(AuthResult.Failed(AuthExpiredException("Google Drive consent required"))))
        assertTrue(GoogleAuthTokenProvider.shouldNotifyReconnect(AuthResult.Failed(java.io.IOException("offline"))))
    }

    @Test
    fun aSignedOutSyncMakesNoRequestAndLeavesTheQueueAlone() = runBlocking {
        val h = SyncHarness()
        h.repo.insertTransaction(h.expense("txn_a"))
        h.googleEmail = null
        val res = h.repo.syncPendingTransactions()
        assertTrue(res is Result.Success)
        assertTrue("no request while signed out", h.server.calls.isEmpty())
        val item = h.qDao.queue.values.single()
        assertEquals(SyncQueueEntity.STATUS_PENDING, item.status)
        assertEquals(0, item.retryCount)
        // signing in again delivers it
        h.googleEmail = "me@example.com"
        h.repo.syncPendingTransactions()
        assertTrue(h.qDao.queue.isEmpty())
    }
}
