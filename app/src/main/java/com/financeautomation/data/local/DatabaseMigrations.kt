package com.financeautomation.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Versioned schema migrations for FinanceDatabase ensuring non-destructive evolution.
 */
object DatabaseMigrations {

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE accounts ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE categories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE categories ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE companions ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE companions ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE companions ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trip_tags ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trip_tags ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE preferences_table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE preferences_table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE transactions SET status = 'Personal' WHERE status = 'Settled' AND owedToMe = 0")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `lineage` (`uuid` TEXT NOT NULL, `carried_from_uuid` TEXT NOT NULL, `root_uuid` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`uuid`))"
            )
            db.execSQL("ALTER TABLE sync_queue ADD COLUMN planJson TEXT")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_captures` (`id` TEXT NOT NULL, `sourcePackage` TEXT NOT NULL, `amount` REAL, `payee` TEXT NOT NULL, `inferredAccount` TEXT, `timestamp` INTEGER NOT NULL, `kind` TEXT NOT NULL, `flags` TEXT NOT NULL DEFAULT '', `state` TEXT NOT NULL, `notificationKey` TEXT, `contentHash` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `handled_notifications` (`key` TEXT NOT NULL, `postTime` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, `handledAt` INTEGER NOT NULL, PRIMARY KEY(`key`, `postTime`))"
            )
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `category_stats` (`payeeKey` TEXT NOT NULL, `category` TEXT NOT NULL, `count` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL, PRIMARY KEY(`payeeKey`, `category`))"
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `pipeline_events` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `time` INTEGER NOT NULL,
                    `stage` TEXT NOT NULL,
                    `code` TEXT NOT NULL,
                    `a` INTEGER,
                    `b` INTEGER
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_pipeline_events_time` ON `pipeline_events` (`time`)"
            )
        }
    }

    val ALL_MIGRATIONS = arrayOf(
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7
    )
}

