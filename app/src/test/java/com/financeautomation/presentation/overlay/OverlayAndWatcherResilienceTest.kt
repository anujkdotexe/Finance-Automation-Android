package com.financeautomation.presentation.overlay

import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.testutil.FakePendingCaptureDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayAndWatcherResilienceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun strandedShownCapturesAreResetToWaiting() = runTest(testDispatcher) {
        val dao = FakePendingCaptureDao()
        val now = System.currentTimeMillis()

        dao.insert(
            PendingCaptureEntity(
                id = "cap_stranded_1",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 250.0,
                payee = "Coffee Shop",
                inferredAccount = "Cash",
                timestamp = now - 60_000L,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_SHOWN,
                notificationKey = null,
                contentHash = null,
                updatedAt = now - 60_000L
            )
        )
        dao.insert(
            PendingCaptureEntity(
                id = "cap_stranded_2",
                sourcePackage = "com.phonepe.app",
                amount = 450.0,
                payee = "Bakery",
                inferredAccount = "UPI",
                timestamp = now - 30_000L,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_SHOWN,
                notificationKey = null,
                contentHash = null,
                updatedAt = now - 30_000L
            )
        )
        dao.insert(
            PendingCaptureEntity(
                id = "cap_already_waiting",
                sourcePackage = "com.google.android.apps.nbu.paisa.user",
                amount = 100.0,
                payee = "Tea",
                inferredAccount = "Cash",
                timestamp = now,
                kind = PendingCaptureEntity.KIND_DEBIT,
                state = PendingCaptureEntity.STATE_WAITING,
                notificationKey = null,
                contentHash = null,
                updatedAt = now
            )
        )

        val resetCount = dao.resetShownToWaiting(updatedAt = now)
        assertEquals(2, resetCount)

        val cap1 = dao.getById("cap_stranded_1")
        val cap2 = dao.getById("cap_stranded_2")
        val cap3 = dao.getById("cap_already_waiting")

        assertNotNull(cap1)
        assertEquals(PendingCaptureEntity.STATE_WAITING, cap1?.state)
        assertEquals(now, cap1?.updatedAt)

        assertNotNull(cap2)
        assertEquals(PendingCaptureEntity.STATE_WAITING, cap2?.state)
        assertEquals(now, cap2?.updatedAt)

        assertNotNull(cap3)
        assertEquals(PendingCaptureEntity.STATE_WAITING, cap3?.state)
    }

    @Test
    fun dismissActionUpdatesCaptureStateToDismissed() = runTest(testDispatcher) {
        val dao = FakePendingCaptureDao()
        val now = System.currentTimeMillis()

        val capture = PendingCaptureEntity(
            id = "cap_swiped_heads_up",
            sourcePackage = "com.google.android.apps.nbu.paisa.user",
            amount = 99.0,
            payee = "Store",
            inferredAccount = "Cash",
            timestamp = now,
            kind = PendingCaptureEntity.KIND_DEBIT,
            state = PendingCaptureEntity.STATE_WAITING,
            notificationKey = null,
            contentHash = null,
            updatedAt = now
        )
        dao.insert(capture)

        // Directly test DAO state transition executed by QuickActionReceiver on ACTION_DISMISS_CAPTURE
        dao.markDismissedIfOpen("cap_swiped_heads_up")

        val updated = dao.getById("cap_swiped_heads_up")
        assertNotNull(updated)
        assertEquals(PendingCaptureEntity.STATE_DISMISSED, updated?.state)
    }
}
