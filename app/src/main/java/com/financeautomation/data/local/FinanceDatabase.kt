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
import com.financeautomation.data.local.dao.BudgetDao
import com.financeautomation.data.local.dao.InflowDao
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.PipelineEventDao
import com.financeautomation.data.local.dao.PreferenceDao
import com.financeautomation.data.local.dao.SubscriptionCycleDao
import com.financeautomation.data.local.dao.SubscriptionDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.data.local.dao.TransactionDao
import com.financeautomation.data.local.dao.TripTagDao
import com.financeautomation.data.local.entity.AccountEntity
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.data.local.entity.CategoryEntity
import com.financeautomation.data.local.entity.CategoryStatEntity
import com.financeautomation.data.local.entity.CompanionEntity
import com.financeautomation.data.local.entity.HandledNotificationEntity
import com.financeautomation.data.local.entity.BudgetEntity
import com.financeautomation.data.local.entity.InflowEntity
import com.financeautomation.data.local.entity.LineageEntity
import com.financeautomation.data.local.entity.PayableEntity
import com.financeautomation.data.local.entity.PendingCaptureEntity
import com.financeautomation.data.local.entity.PipelineEventEntity
import com.financeautomation.data.local.entity.PreferenceEntity
import com.financeautomation.data.local.entity.SubscriptionCycleEntity
import com.financeautomation.data.local.entity.SubscriptionEntity
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
        PayableEntity::class,
        SubscriptionEntity::class,
        SubscriptionCycleEntity::class,
        InflowEntity::class,
        BudgetEntity::class
    ],
    version = 15,
    exportSchema = true
)
abstract class FinanceDatabase : RoomDatabase() {

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
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun subscriptionCycleDao(): SubscriptionCycleDao
    abstract fun inflowDao(): InflowDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        const val DATABASE_NAME: String = "finance_automation.db"
        const val RESET_NOTICE_FILE: String = "storage_reset_notice"
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
        val MIGRATION_8_9 = DatabaseMigrations.MIGRATION_8_9
        val MIGRATION_9_10 = DatabaseMigrations.MIGRATION_9_10
        val MIGRATION_10_11 = DatabaseMigrations.MIGRATION_10_11
        val MIGRATION_11_12 = DatabaseMigrations.MIGRATION_11_12
        val MIGRATION_12_13 = DatabaseMigrations.MIGRATION_12_13
        val MIGRATION_13_14 = DatabaseMigrations.MIGRATION_13_14

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

            // Step 1: Obtain encryption passphrase with one short retry. There is no in-memory fallback:
            // saves there would be lost. A failure throws StorageUnavailableException and the next access retries.
            val passphrase: ByteArray = PassphraseAccess.obtain(
                provider = { keyProvider.getOrCreatePassphrase() },
                pause = {
                    Log.w(TAG, "Transient Keystore error. Retrying once.")
                    // Never sleep on the main thread
                    if (Looper.myLooper() != Looper.getMainLooper()) Thread.sleep(150)
                }
            )

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
                        StorageResetNotice(File(appContext.filesDir, RESET_NOTICE_FILE)).mark()
                        UnreadableDatabaseRetention.prune(dbFile.parentFile ?: appContext.filesDir, DATABASE_NAME, keep = 2)
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
                            Log.e(TAG, "Canary retry failed: ${retryEx.javaClass.simpleName}. Storage unavailable.")
                            throw StorageUnavailableException("The database could not be opened. Try again.", retryEx)
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

            // Open database so Room initializes the encrypted schema on disk before any restoration
            try {
                db.openHelper.writableDatabase
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize writable database: ${e.javaClass.simpleName}")
            }

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
                        id = "acc_cash",
                        name = "Cash",
                        lastFourDigits = null,
                        isDefault = false,
                        isArchived = false,
                        displayOrder = 0
                    ),
                    AccountEntity(
                        id = "acc_other",
                        name = CategoryConstants.ACCOUNT_OTHER,
                        lastFourDigits = null,
                        isDefault = false,
                        isArchived = false,
                        displayOrder = 1
                    )
                )
                accountDao.insertAll(presetAccounts)
            }

            val preferenceDao = database.preferenceDao()
            if (preferenceDao.getPreference(PreferencesRepository.KEY_DEFAULT_ACCOUNT) == null) {
                preferenceDao.setPreference(
                    PreferenceEntity(
                        key = PreferencesRepository.KEY_DEFAULT_ACCOUNT,
                        value = CategoryConstants.ACCOUNT_OTHER
                    )
                )
            }
            if (preferenceDao.getPreference(PreferencesRepository.KEY_SETTLE_DELAY_SECONDS) == null) {
                preferenceDao.setPreference(
                    PreferenceEntity(
                        key = PreferencesRepository.KEY_SETTLE_DELAY_SECONDS,
                        value = "120"
                    )
                )
            }
        }
    }
}
