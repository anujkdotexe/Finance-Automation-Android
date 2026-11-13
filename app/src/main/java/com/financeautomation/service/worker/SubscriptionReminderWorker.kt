package com.financeautomation.service.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.financeautomation.FinanceApplication
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.domain.model.Subscription
import com.financeautomation.domain.model.SubscriptionCycle
import com.financeautomation.domain.model.SubscriptionCycleState
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.SubscriptionRepository
import com.financeautomation.domain.usecase.SubscriptionCycleSync
import com.financeautomation.domain.usecase.SubscriptionScheduleUseCase
import com.financeautomation.service.SubscriptionNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Posts one reminder notification for a due subscription cycle. */
fun interface SubscriptionReminderNotifier {
    fun post(subscription: Subscription, cycle: SubscriptionCycle)
}

/**
 * Daily worker (24 hours, 15 minute flex, started at the user's reminder time, default 21:00 Asia/Kolkata).
 * 1. Creates missing cycles for each active subscription up to today, never before the current month
 * (Asia/Kolkata) and never before the day the subscription was created.
 * 2. Posts one notification per DUE cycle with reminders on and counts the reminder.
 * 3. After the third reminder the cycle moves to NEEDS_REVIEW and reminding stops.
 */
class SubscriptionReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as? FinanceApplication ?: return@withContext Result.retry()
        try {
            val container = app.appContainer
            val today = java.time.LocalDate.now(SubscriptionScheduleUseCase.IST).toEpochDay()
            runCheck(
                repository = container.subscriptionRepository,
                scheduleUseCase = SubscriptionScheduleUseCase(),
                notifier = SubscriptionNotifier(applicationContext, appLockOn = com.financeautomation.service.NotificationPrivacy.isAppLockOn(applicationContext)),
                todayEpochDay = today
            )
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Diagnostics.record("SubscriptionReminderWorker", "${e.javaClass.simpleName}: doWork")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME: String = "subscription_reminder_periodic"
        const val WORK_NAME_ONCE: String = "subscription_reminder_once"
        const val MAX_REMINDERS: Int = 3
        const val FLEX_MINUTES: Long = 15

        /**
         * Core of the worker, free of Android dependencies so it can be unit tested.
         * Returns the number of notifications posted.
         */
        suspend fun runCheck(
            repository: SubscriptionRepository,
            scheduleUseCase: SubscriptionScheduleUseCase,
            notifier: SubscriptionReminderNotifier,
            todayEpochDay: Long
        ): Int {
            SubscriptionCycleSync.ensure(repository, scheduleUseCase, todayEpochDay)

            var posted = 0
            for (item in repository.getDueCyclesUpTo(todayEpochDay)) {
                if (!item.subscription.remind || item.cycle.state != SubscriptionCycleState.DUE) continue
                notifier.post(item.subscription, item.cycle)
                posted++
                val count = item.cycle.remindCount + 1
                val nextState = if (count >= MAX_REMINDERS) SubscriptionCycleState.NEEDS_REVIEW else SubscriptionCycleState.DUE
                repository.setCycleState(item.cycle.id, nextState, count)
            }
            return posted
        }

        /** Delay until the next occurrence of [reminderTime] (HH:mm) in Asia/Kolkata. */
        fun initialDelay(reminderTime: String, now: ZonedDateTime = ZonedDateTime.now(SubscriptionScheduleUseCase.IST)): Duration {
            val normalized = PreferencesRepository.normalizeReminderTime(reminderTime)
            val (h, m) = normalized.split(":").map { it.toInt() }
            var next = now.with(LocalTime.of(h, m))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }

        private const val TIME_TAG_PREFIX = "reminder_time:"

        fun timeTag(time: String): String = TIME_TAG_PREFIX + time

        fun timeFromTags(tags: Set<String>): String? =
            tags.firstOrNull { it.startsWith(TIME_TAG_PREFIX) }?.removePrefix(TIME_TAG_PREFIX)

        /**
         * KEEP while the pending run was scheduled for the same time, so opening the app never cancels a run that is
         * about to fire; otherwise (new time, or nothing pending) replace it.
         */
        fun policyFor(existingTags: Set<String>?, newTime: String): ExistingPeriodicWorkPolicy =
            if (existingTags != null && timeFromTags(existingTags) == newTime) {
                ExistingPeriodicWorkPolicy.KEEP
            } else {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            }

        /** Schedules the daily worker at [reminderTime]. Call from a background thread. A changed time reschedules it. */
        fun schedule(context: Context, reminderTime: String) {
            val manager = WorkManager.getInstance(context)
            val pendingTags = try {
                manager.getWorkInfosForUniqueWork(WORK_NAME).get()
                    .firstOrNull { !it.state.isFinished }?.tags
            } catch (e: Exception) {
                null
            }
            val request = PeriodicWorkRequestBuilder<SubscriptionReminderWorker>(
                24, TimeUnit.HOURS, FLEX_MINUTES, TimeUnit.MINUTES
            )
                .setInitialDelay(initialDelay(reminderTime).toMillis(), TimeUnit.MILLISECONDS)
                .addTag(timeTag(reminderTime))
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, policyFor(pendingTags, reminderTime), request)
        }

        /** Developer mode: runs the check once, immediately. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SubscriptionReminderWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_ONCE,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
