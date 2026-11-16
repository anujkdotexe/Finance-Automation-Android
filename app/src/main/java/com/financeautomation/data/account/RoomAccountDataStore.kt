package com.financeautomation.data.account

import androidx.room.withTransaction
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.flow.first

/** The Room implementation of [AccountDataStore]. */
class RoomAccountDataStore(
    private val cacheDir: java.io.File? = null,
    private val databaseProvider: suspend () -> FinanceDatabase
) : AccountDataStore {

    override suspend fun pendingCount(): Int =
        databaseProvider().syncQueueDao().getAllQueueFlow().first()
            .count { it.operationType != SyncQueueEntity.OP_CONFIG_PUSH }

    override suspend fun hasLocalData(): Boolean {
        val db = databaseProvider()
        return db.transactionDao().getTransactionsFlow().first().isNotEmpty() ||
            db.inflowDao().getAllInflows().first().isNotEmpty() ||
            db.syncQueueDao().getAllQueueFlow().first().isNotEmpty()
    }

    override suspend fun wipe() {
        // The previous account's CSV exports must not stay in the cache
        AccountCacheCleaner.clearExports(cacheDir)
        val db = databaseProvider()
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            for (table in WIPED_TABLES) sql.execSQL("DELETE FROM $table")
            // Preferences: keep only app level settings; everything else (workbook ids, sync times, hints tied to the
            // account, the Google profile) goes.
            val keep = KEPT_PREFERENCE_KEYS.joinToString(",") { "'$it'" }
            sql.execSQL("DELETE FROM preferences_table WHERE `key` NOT IN ($keep)")
            // Defaults are seeded in the same transaction, so a kill can never leave no categories or accounts
            FinanceDatabase.prepopulateDefaults(db)
        }
    }

    companion object {
        /** Every per-account table. Pipeline events are diagnostics of the app and the preferences table is handled apart. */
        val WIPED_TABLES: List<String> = listOf(
            "transactions", "companions", "categories", "sync_queue", "accounts", "trip_tags", "lineage",
            "pending_captures", "handled_notifications", "category_stats", "payables", "subscriptions",
            "subscription_cycles", "inflows", "budgets"
        )

        /** Tables that survive an account switch on purpose. */
        val KEPT_TABLES: List<String> = listOf("preferences_table", "pipeline_events")

        /** App level preferences that survive: theme, lock and privacy, developer and timing settings, hints. */
        val KEPT_PREFERENCE_KEYS: List<String> = listOf(
            "key_theme_mode", "app_lock_enabled", "hide_recents_preview", "key_developer_mode", "device_id",
            "key_settle_delay_seconds", "subscription_reminder_time", "dashboard_hint_dismissed",
            "needs_review_hint_dismissed", "oem_setup_completed", AccountSwitchGuard.KEY_LEDGER_OWNER
        )
    }
}
