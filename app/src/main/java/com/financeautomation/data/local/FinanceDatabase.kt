package com.financeautomation.data.local

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.data.local.dao.AccountDao
import com.financeautomation.data.local.dao.CategoryDao
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.CompanionDao
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.PipelineEventDao
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.local.entity.CategoryStatEntity
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.local.entity.HandledNotificationEntity
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.PipelineEventEntity
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.local.entity.SyncQueueEntity
import com.financeautomation.data.local.entity.TransactionEntity
import com.financeautomation.data.local.entity.TripTagEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * Main Room SQLite database for Finance Automation Android.
 * Manages all 7 entities: Transactions, Companions, Categories, SyncQueue,
 * Accounts, TripTags, and Preferences.
 *
 * Migration & Integrity Guarantees:
 * 1. Encrypted with SQLCipher using raw hex AES key derived from Keystore.
 * 2. Destructive migrations are strictly forbidden. Every Room entity change requires
 *    a version bump and an explicit Migration that never drops the sync queue.
 * 3. exportSchema is set to true to generate versioned schema contracts in schemas/.
 * 4. Unreadable databases (e.g. after permanent key loss) are safely moved aside
 *    (finance_automation.db.unreadable_<timestamp>) and rebuilt from cloud without crash loops.
 */
@Database(
    entities = [
        TransactionEntity::class,
        CompanionEntity::class,
        CategoryEntity::class,
        SyncQueueEntity::class,
        AccountEntity::class,
        TripTagEntity::class,
        PreferenceEntity::class,
        LineageEntity::class,
        PendingCaptureEntity::class,
        HandledNotificationEntity::class,
        CategoryStatEntity::class,
        PipelineEventEntity::class,
        PayableEntity::class
    ],
    version = 8,
    exportSchema = true
)
abstract class FinanceDatabase : RoomDatabase() {

    var isStorageTemporarilyUnavailable: Boolean = false

    abstract fun transactionDao(): TransactionDao
    abstract fun companionDao(): CompanionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun syncQueueDao(): SyncQueueDao
    abstract fun accountDao(): AccountDao
    abstract fun tripTagDao(): TripTagDao
    abstract fun preferenceDao(): PreferenceDao
    abstract fun lineageDao(): LineageDao
    abstract fun pendingCaptureDao(): PendingCaptureDao
    abstract fun handledNotificationDao(): HandledNotificationDao
    abstract fun categoryStatDao(): CategoryStatDao
    abstract fun pipelineEventDao(): PipelineEventDao
    abstract fun payableDao(): PayableDao

    companion object {
        const val DATABASE_NAME: String = "finance_automation.db"
        private const val TAG = "FinanceDatabase"

        init {
            System.loadLibrary("sqlcipher")
        }

        @Volatile
        private var INSTANCE: FinanceDatabase? = null

        val MIGRATION_2_3 = DatabaseMigrations.MIGRATION_2_3
        val MIGRATION_3_4 = DatabaseMigrations.MIGRATION_3_4
        val MIGRATION_4_5 = DatabaseMigrations.MIGRATION_4_5
        val MIGRATION_5_6 = DatabaseMigrations.MIGRATION_5_6
        val MIGRATION_6_7 = DatabaseMigrations.MIGRATION_6_7
        val MIGRATION_7_8 = DatabaseMigrations.MIGRATION_7_8

        fun getInstance(context: Context): FinanceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        throw IllegalStateException(
                            "FinanceDatabase must not be initialized synchronously on the main thread. " +
                            "Pre-warm in FinanceApplication.onCreate on Dispatchers.IO or initialize from a background dispatcher."
                        )
                    }
                    val db = buildDatabase(context.applicationContext)
                    db.also { INSTANCE = it }
                }
            }
        }

        suspend fun getInstanceAsync(context: Context): FinanceDatabase {
            return INSTANCE ?: withContext(Dispatchers.IO) {
                getInstance(context)
            }
        }

        private fun buildDatabase(context: Context): FinanceDatabase {
            val appContext = context.applicationContext
            val keyProvider = DatabaseKeyProvider(appContext)

            // Step 1: Obtain encryption passphrase with transient retry guard
            var passphrase: ByteArray? = null
            try {
                passphrase = keyProvider.getOrCreatePassphrase()
            } catch (e: Exception) {
                Log.w(TAG, "Transient Keystore error: ${e.javaClass.simpleName}. Retrying once.")
                try {
                    // Retry with 150ms delay on background thread (never sleep or yield on main thread)
                    if (Looper.myLooper() != Looper.getMainLooper()) {
                        Thread.sleep(150)
                    }
                    passphrase = keyProvider.getOrCreatePassphrase()
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Keystore unavailable after retry: ${retryEx.javaClass.simpleName}. Starting in storage-temporarily-unavailable mode.")
                }
            }

            // If Keystore is temporarily unavailable or locked, start in-memory without crashing or deleting key file
            if (passphrase == null) {
                return createInMemory(appContext).also {
                    it.isStorageTemporarilyUnavailable = true
                }
            }

            // Step 2: One-time plaintext SQLite migration. On failure, returns pointer to moved-aside plain backup.
            val failedPlainBackup = DatabaseEncryptionMigrator.migrateIfNeeded(
                context = appContext,
                dbName = DATABASE_NAME,
                keyProvider = keyProvider
            )

            // Step 3: Canary integrity check: verify database can be opened with the active key.
            val dbFile = appContext.getDatabasePath(DATABASE_NAME)
            if (dbFile.exists() && dbFile.length() > 0) {
                try {
                    val canary = SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        passphrase,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                        null
                    )
                    canary.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
                    canary.close()
                } catch (e: Exception) {
                    val msg = e.message?.lowercase(java.util.Locale.ROOT).orEmpty()
                    val isKeyOrNotDb = msg.contains("file is not a database") ||
                            msg.contains("bad decrypt") ||
                            msg.contains("not a database") ||
                            e is android.database.sqlite.SQLiteDatabaseCorruptException ||
                            e.javaClass.simpleName.contains("Corrupt")

                    if (isKeyOrNotDb) {
                        // Database cannot be opened with the current key.
                        // The unsynced queue in an unreadable database cannot be recovered because the data is encrypted with an unavailable key.
                        // Move aside to finance_automation.db.unreadable_<timestamp> and create a fresh encrypted database.
                        Log.e(TAG, "Database key invalid or file not a database: ${e.javaClass.simpleName}. Moving aside to $DATABASE_NAME.unreadable")
                        val unreadableFile = File(dbFile.parentFile, "$DATABASE_NAME.unreadable_${System.currentTimeMillis()}")
                        dbFile.renameTo(unreadableFile)
                        val walFile = File(dbFile.parentFile, "$DATABASE_NAME-wal")
                        val shmFile = File(dbFile.parentFile, "$DATABASE_NAME-shm")
                        if (walFile.exists()) walFile.delete()
                        if (shmFile.exists()) shmFile.delete()
                    } else {
                        // Transient problem (lock, I/O): retry once with real delay on background thread; rethrow if it fails again
                        Log.w(TAG, "Transient canary error: ${e.javaClass.simpleName}. Retrying once.")
                        try {
                            if (Looper.myLooper() != Looper.getMainLooper()) {
                                Thread.sleep(150)
                            }
                            val canaryRetry = SQLiteDatabase.openDatabase(
                                dbFile.absolutePath,
                                passphrase,
                                null,
                                SQLiteDatabase.OPEN_READONLY,
                                null
                            )
                            canaryRetry.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
                            canaryRetry.close()
                        } catch (retryEx: Exception) {
                            Log.e(TAG, "Canary retry failed: ${retryEx.javaClass.simpleName}. Rethrowing transient error.")
                            throw retryEx
                        }
                    }
                }
            }

            // Step 4: Build Room database
            val openHelperFactory = SupportOpenHelperFactory(passphrase)

            val db = Room.databaseBuilder(
                appContext,
                FinanceDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(openHelperFactory)
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                .addCallback(DatabasePrepopulateCallback())
                .build()

            // Step 5: If a plain migration had failed, copy plain backup rows table by table
            if (failedPlainBackup != null && failedPlainBackup.exists()) {
                DatabaseEncryptionMigrator.restorePlainBackupIntoEncryptedDb(
                    encryptedDbPath = dbFile.absolutePath,
                    passphrase = passphrase,
                    plainBackupFile = failedPlainBackup
                )
            }

            return db
        }

        fun createInMemory(context: Context): FinanceDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                FinanceDatabase::class.java
            )
                .addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)
                .allowMainThreadQueries()
                .build()
        }

        private class DatabasePrepopulateCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    INSTANCE?.let { database ->
                        prepopulateDefaults(database)
                    }
                }
            }
        }

        suspend fun prepopulateDefaults(database: FinanceDatabase) {
            val categoryDao = database.categoryDao()
            if (categoryDao.count() == 0) {
                val defaults = CategoryConstants.DEFAULT_CATEGORIES.mapIndexed { index, name ->
                    val isSavings = name.equals("Savings", ignoreCase = true)
                    CategoryEntity(
                        id = "cat_${index + 1}",
                        name = name,
                        isDefault = true,
                        isExcludedFromLivingBurn = isSavings,
                        isArchived = false,
                        displayOrder = index
                    )
                }
                categoryDao.insertAll(defaults)
            }

            val accountDao = database.accountDao()
            if (accountDao.count() == 0) {
                val presetAccounts = listOf(
                    AccountEntity(
                        id = "acc_1",
                        name = CategoryConstants.ACCOUNT_SBI,
                        lastFourDigits = null,
                        isDefault = true,
                        isArchived = false,
                        displayOrder = 0
                    ),
                    AccountEntity(
                        id = "acc_2",
                        name = CategoryConstants.ACCOUNT_CANARA,
                        lastFourDigits = null,
                        isDefault = false,
                        isArchived = false,
                        displayOrder = 1
                    ),
                    AccountEntity(
                        id = "acc_3",
                        name = "Cash",
                        lastFourDigits = null,
                        isDefault = false,
                        isArchived = false,
                        displayOrder = 2
                    ),
                    AccountEntity(
                        id = "acc_4",
                        name = "Other",
                        lastFourDigits = null,
                        isDefault = false,
                        isArchived = false,
                        displayOrder = 3
                    )
                )
                accountDao.insertAll(presetAccounts)
            }

            val preferenceDao = database.preferenceDao()
            if (preferenceDao.getPreference("key_default_account") == null) {
                preferenceDao.setPreference(
                    PreferenceEntity(
                        key = "key_default_account",
                        value = CategoryConstants.ACCOUNT_SBI
                    )
                )
            }
            if (preferenceDao.getPreference("key_settle_delay_seconds") == null) {
                preferenceDao.setPreference(
                    PreferenceEntity(
                        key = "key_settle_delay_seconds",
                        value = "120"
                    )
                )
            }
        }
    }
}
