package com.financeautomation.service

import androidx.work.ExistingPeriodicWorkPolicy
import com.financeautomation.service.worker.SubscriptionReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** R2-13: a process start must not cancel the pending reminder run; only a changed time reschedules it. */
class ReminderSchedulePolicyTest {

    @Test
    fun sameTimeKeepsThePendingRun() {
        val tags = setOf("other", SubscriptionReminderWorker.timeTag("21:00"))
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, SubscriptionReminderWorker.policyFor(tags, "21:00"))
    }

    @Test
    fun changedTimeReschedules() {
        val tags = setOf(SubscriptionReminderWorker.timeTag("21:00"))
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, SubscriptionReminderWorker.policyFor(tags, "20:30"))
    }

    @Test
    fun noPendingWorkSchedulesIt() {
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, SubscriptionReminderWorker.policyFor(emptySet(), "21:00"))
        assertEquals(ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, SubscriptionReminderWorker.policyFor(null, "21:00"))
    }

    @Test
    fun timeTagRoundTrips() {
        assertEquals("21:00", SubscriptionReminderWorker.timeFromTags(setOf(SubscriptionReminderWorker.timeTag("21:00"))))
        assertNull(SubscriptionReminderWorker.timeFromTags(setOf("unrelated")))
    }
}
