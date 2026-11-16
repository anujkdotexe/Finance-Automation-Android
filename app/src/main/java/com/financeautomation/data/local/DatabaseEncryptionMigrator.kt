package com.financeautomation.data.local

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.FileInputStream

/**
 * Handles one-time offline migration of an existing unencrypted Room SQLite database
 * to an AES-256 encrypted SQLCipher database.
 *
 * Guarantees:
 * 1. Checks 16-byte SQLite magic header to detect plaintext status.
 * 2. Runs `sqlcipher_export` using `rawExecSQL` (not `execSQL`) to safely execute statements that return a row.
 * 3. Uses SQLCipher's native `ATTACH ... KEY "x'<hex>'"` and matching `"x'<hex>'".toByteArray()` raw key formatting.
 * 4. Verifies database integrity and per-table row counts before deleting plaintext file.
 * 5. On migration failure, never throws out of buildDatabase. Moves plaintext file aside,
 *    and restores both referenced transactions rows and sync_queue rows into the fresh encrypted database
 *    (transactions before sync_queue, table by table, without logging row contents).
 */
object DatabaseEncryptionMigrator {

    private const val TAG = "DatabaseEncryptionMigrator"
    private val SQLITE3_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    private val MONITORED_TABLES = listOf(
        "transactions",
        "sync_queue",
        "categories",
        "accounts",
        "companions",
        "trip_tags",
        "preferences_table",
        "preferences",
        "lineage",
        "pending_captures",
        "handled_notifications"
    )

    /**
     * Checks if the database file at [dbName] is unencrypted, and if so, migrates it
     * to SQLCipher encryption using the key provided by [keyProvider].
     *
     * Returns the File pointer to the moved-aside plain backup if migration failed,
     * or null if migration succeeded or was not required.
     */
    @Synchronized
    fun migrateIfNeeded(
        context: Context,
        dbName: String,
        keyProvider: DatabaseKeyProvider
    ): File? {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists() || dbFile.length() < 16) {
            return null
        }

        if (!isPlaintextDatabase(dbFile)) {
            // Already encrypted or empty; nothing to migrate.
            return null
        }

        Log.i(TAG, "Unencrypted SQLite database detected. Starting SQLCipher migration.")
        val encFile = File(dbFile.parentFile, "$dbName.enc")
        if (encFile.exists()) {
            encFile.delete()
        }

        var plainDb: SQLiteDatabase? = null
        val cachedCounts = mutableMapOf<String, Long>()

        try {
            // 1. Open plain database using SQLCipher with empty password
            plainDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                "",
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )

            // 2. Record pre-migration row counts for all tables
            for (table in MONITORED_TABLES) {
                try {
                    plainDb.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            cachedCounts[table] = cursor.getLong(0)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Table count check skipped for $table: ${e.javaClass.simpleName}")
                }
            }

            // 3. Export to encrypted copy using sqlcipher_export with x'<hex>' raw key via rawExecSQL
            val hexKey = keyProvider.getDatabaseHexKey()
            val escapedEncPath = encFile.absolutePath.replace("'", "''")

            plainDb.execSQL("ATTACH DATABASE '$escapedEncPath' AS encrypted KEY \"$hexKey\";")
            // rawExecSQL is used because SELECT sqlcipher_export('encrypted') returns a row; execSQL throws for row-returning queries
            plainDb.rawExecSQL("SELECT sqlcipher_export('encrypted');")
            plainDb.execSQL("DETACH DATABASE encrypted;")
            plainDb.close()
            plainDb = null

            // 4. Verify encrypted database integrity and row counts using x'<hex>' key bytes
            val keyBytes = keyProvider.getOrCreatePassphrase()
            val encDb = SQLiteDatabase.openDatabase(
                encFile.absolutePath,
                keyBytes,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null
            )

            try {
                if (!encDb.isDatabaseIntegrityOk) {
                    throw IllegalStateException("Encrypted database failed integrity check")
                }

                for ((table, expectedCount) in cachedCounts) {
                    encDb.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            val actualCount = cursor.getLong(0)
                            if (actualCount != expectedCount) {
                                throw IllegalStateException(
                                    "Row count mismatch for table $table: expected $expectedCount, found $actualCount"
                                )
                            }
                        }
                    }
                }
            } finally {
                encDb.close()
            }

            // 5. Swap files: securely delete plain database and companion files, rename encrypted copy
            val walFile = File(dbFile.parentFile, "$dbName-wal")
            val shmFile = File(dbFile.parentFile, "$dbName-shm")
            if (walFile.exists()) securelyDeleteFile(walFile)
            if (shmFile.exists()) securelyDeleteFile(shmFile)

            securelyDeleteFile(dbFile)
            if (!encFile.renameTo(dbFile)) {
                throw IllegalStateException("Failed to rename encrypted database to target name")
            }

            Log.i(TAG, "SQLCipher database migration completed successfully. All ${cachedCounts.size} tables verified.")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Database encryption migration failed: ${e.javaClass.simpleName}")
            plainDb?.close()
            if (encFile.exists()) {
                encFile.delete()
            }

            // A failed plain-to-encrypted migration must not throw out of buildDatabase.
            // Move plain file aside, allow fresh encrypted database to be created, and return plain backup file
            // so referenced transactions and sync_queue rows can be restored table by table.
            val failedFile = File(dbFile.parentFile, "$dbName.plain_migration_failed_${System.currentTimeMillis()}")
            dbFile.renameTo(failedFile)
            val walFile = File(dbFile.parentFile, "$dbName-wal")
            val shmFile = File(dbFile.parentFile, "$dbName-shm")
            if (walFile.exists()) walFile.delete()
            if (shmFile.exists()) shmFile.delete()

            Log.w(TAG, "Plain database moved to ${failedFile.name}. Retaining file for table-by-table fallback recovery.")
            return failedFile
        }
    }

    /**
     * Attaches the moved-aside plain database backup to the newly created encrypted database,
     * and copies rows table-by-table (transactions before sync_queue, best effort, zero row logging).
     */
    fun restorePlainBackupIntoEncryptedDb(
        encryptedDbPath: String,
        passphrase: ByteArray,
        plainBackupFile: File
    ) {
        if (!plainBackupFile.exists() || plainBackupFile.length() < 16) return

        var encDb: SQLiteDatabase? = null
        try {
            encDb = SQLiteDatabase.openDatabase(
                encryptedDbPath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null
            )
            val escapedPath = plainBackupFile.absolutePath.replace("'", "''")
            encDb.execSQL("ATTACH DATABASE '$escapedPath' AS plain_backup KEY '';")

            // 1. Transactions table FIRST (so foreign key / transactionId references exist when sync_queue rows arrive)
            try {
                encDb.execSQL("INSERT OR IGNORE INTO transactions SELECT * FROM plain_backup.transactions;")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback restore transactions failed: ${e.javaClass.simpleName}")
            }

            // 2. Sync queue table SECOND
            try {
                encDb.execSQL("INSERT OR IGNORE INTO sync_queue SELECT * FROM plain_backup.sync_queue;")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback restore sync_queue failed: ${e.javaClass.simpleName}")
            }

            // 3. Other config tables best effort
            val otherTables = listOf("categories", "accounts", "companions", "trip_tags", "preferences_table", "preferences")
            for (table in otherTables) {
                try {
                    encDb.execSQL("INSERT OR IGNORE INTO $table SELECT * FROM plain_backup.$table;")
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback restore table $table skipped: ${e.javaClass.simpleName}")
                }
            }

            try {
                encDb.execSQL("DETACH DATABASE plain_backup;")
            } catch (e: Exception) {
                Log.w(TAG, "Fallback detach plain_backup failed: ${e.javaClass.simpleName}")
            }

            Log.i(TAG, "Plain backup fallback restoration completed table-by-table.")
            securelyDeleteFile(plainBackupFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach plain backup for recovery: ${e.javaClass.simpleName}")
        } finally {
            encDb?.close()
        }
    }

    fun securelyDeleteFile(file: File) {
        if (!file.exists()) return
        try {
            val length = file.length()
            if (length > 0) {
                java.io.RandomAccessFile(file, "rws").use { raf ->
                    val zeroBuffer = ByteArray(4096)
                    var written = 0L
                    while (written < length) {
                        val toWrite = minOf(zeroBuffer.size.toLong(), length - written).toInt()
                        raf.write(zeroBuffer, 0, toWrite)
                        written += toWrite
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Secure wipe failed before deletion: ${e.javaClass.simpleName}")
        } finally {
            file.delete()
        }
    }

    private fun isPlaintextDatabase(file: File): Boolean {
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(16)
                val read = fis.read(header)
                if (read == 16) {
                    return header.contentEquals(SQLITE3_MAGIC)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect database header: ${e.javaClass.simpleName}")
        }
        return false
    }
}
