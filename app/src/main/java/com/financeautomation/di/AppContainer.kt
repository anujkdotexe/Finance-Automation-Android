package com.financeautomation.di

import android.content.Context
import com.financeautomation.data.local.ConfigSyncLocalGatewayImpl
import com.financeautomation.data.local.FinanceDatabase
import com.financeautomation.data.remote.AccountingSyncExecutor
import com.financeautomation.data.remote.ConfigSheetManager
import com.financeautomation.data.remote.ConfigSyncCoordinator
import com.financeautomation.data.remote.GoogleAuthTokenProvider
import com.financeautomation.data.remote.GoogleDriveClient
import com.financeautomation.data.remote.GoogleSheetsClient
import com.financeautomation.data.remote.TokenProvider
import com.financeautomation.data.remote.TokenRecoveryInterceptor
import com.financeautomation.data.remote.WorkbookResolver
import com.financeautomation.data.repository.AccountRepositoryImpl
import com.financeautomation.data.repository.CategoryRepositoryImpl
import com.financeautomation.data.repository.ConfigRepositoryImpl
import com.financeautomation.data.repository.PeopleRepositoryImpl
import com.financeautomation.data.repository.PreferencesRepositoryImpl
import com.financeautomation.data.repository.TransactionRepositoryImpl
import com.financeautomation.data.repository.TripTagRepositoryImpl
import com.financeautomation.data.local.dao.LineageDao
import com.financeautomation.data.local.dao.HandledNotificationDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.service.ForegroundWatcher
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.ConfigRepository
import com.financeautomation.domain.repository.ConfigSyncLocalGateway
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.CarryForwardUseCase
import com.financeautomation.domain.usecase.NettingUseCase
import com.financeautomation.domain.usecase.ParseNotificationUseCase
import com.financeautomation.domain.usecase.RegenerateDashboardUseCase
import com.financeautomation.domain.usecase.RepaymentWaterfallUseCase
import com.financeautomation.domain.usecase.SettlementWaterfallUseCase
import com.financeautomation.domain.usecase.SyncConfigUseCase
import com.financeautomation.domain.usecase.SyncTransactionsUseCase
import com.financeautomation.domain.usecase.VoidTransactionUseCase
import com.financeautomation.domain.usecase.YearCloseUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual Dependency Injection container providing application-scoped singletons
 * without reflection or runtime bytecode generation.
 */
interface AppContainer {
    val applicationContext: Context
    val ioDispatcher: CoroutineDispatcher
    val mainDispatcher: CoroutineDispatcher
    val defaultDispatcher: CoroutineDispatcher
    val tokenProvider: TokenProvider?
    val googleAuthTokenProvider: GoogleAuthTokenProvider
    val okHttpClient: OkHttpClient
    val database: FinanceDatabase
    val googleDriveClient: GoogleDriveClient
    val googleSheetsClient: GoogleSheetsClient
    val workbookResolver: WorkbookResolver
    val transactionRepository: TransactionRepository
    val preferencesRepository: PreferencesRepository
    val peopleRepository: PeopleRepository
    val accountRepository: AccountRepository
    val categoryRepository: CategoryRepository
    val tripTagRepository: TripTagRepository
    val configRepository: ConfigRepository
    val configSyncLocalGateway: ConfigSyncLocalGateway
    val configSyncCoordinator: ConfigSyncCoordinator
    val configSheetManager: ConfigSheetManager
    val parseNotificationUseCase: ParseNotificationUseCase
    val syncTransactionsUseCase: SyncTransactionsUseCase
    val syncConfigUseCase: SyncConfigUseCase
    val regenerateDashboardUseCase: RegenerateDashboardUseCase
    val lineageDao: LineageDao
    val calculateSplitUseCase: CalculateSplitUseCase
    val nettingUseCase: NettingUseCase
    val settlementWaterfallUseCase: SettlementWaterfallUseCase
    val repaymentWaterfallUseCase: RepaymentWaterfallUseCase
    val carryForwardUseCase: CarryForwardUseCase
    val voidTransactionUseCase: VoidTransactionUseCase
    val yearCloseUseCase: YearCloseUseCase
    val accountingSyncExecutor: AccountingSyncExecutor
    val pendingCaptureDao: PendingCaptureDao
    val handledNotificationDao: HandledNotificationDao
    val foregroundWatcher: ForegroundWatcher
}

/**
 * Default implementation of [AppContainer] instantiated at application startup.
 */
class DefaultAppContainer(
    override val applicationContext: Context,
    providedTokenProvider: TokenProvider? = null
) : AppContainer {

    override val tokenProvider: TokenProvider = providedTokenProvider
        ?: GoogleAuthTokenProvider(applicationContext)

    override val googleAuthTokenProvider: GoogleAuthTokenProvider
        get() = (tokenProvider as? GoogleAuthTokenProvider) ?: GoogleAuthTokenProvider(applicationContext)

    override val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    override val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
    override val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    override val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(TokenRecoveryInterceptor(tokenProvider))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override val database: FinanceDatabase by lazy {
        FinanceDatabase.getInstance(applicationContext)
    }

    override val googleDriveClient: GoogleDriveClient by lazy {
        GoogleDriveClient(
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider
        )
    }

    override val googleSheetsClient: GoogleSheetsClient by lazy {
        GoogleSheetsClient(
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider
        )
    }

    override val preferencesRepository: PreferencesRepository by lazy {
        PreferencesRepositoryImpl(
            preferenceDao = database.preferenceDao(),
            ioDispatcher = ioDispatcher
        ).also { impl ->
            impl.configSyncCoordinator = configSyncCoordinator
        }
    }

    override val peopleRepository: PeopleRepository by lazy {
        PeopleRepositoryImpl(
            companionDao = database.companionDao(),
            configSyncCoordinator = configSyncCoordinator,
            ioDispatcher = ioDispatcher
        )
    }

    override val accountRepository: AccountRepository by lazy {
        AccountRepositoryImpl(
            accountDao = database.accountDao(),
            configSyncCoordinator = configSyncCoordinator,
            ioDispatcher = ioDispatcher
        )
    }

    override val categoryRepository: CategoryRepository by lazy {
        CategoryRepositoryImpl(
            categoryDao = database.categoryDao(),
            configSyncCoordinator = configSyncCoordinator,
            ioDispatcher = ioDispatcher
        )
    }

    override val tripTagRepository: TripTagRepository by lazy {
        TripTagRepositoryImpl(
            tripTagDao = database.tripTagDao(),
            configSyncCoordinator = configSyncCoordinator,
            ioDispatcher = ioDispatcher
        )
    }

    override val configRepository: ConfigRepository by lazy {
        ConfigRepositoryImpl(
            googleSheetsClient = googleSheetsClient,
            ioDispatcher = ioDispatcher
        )
    }

    override val configSyncLocalGateway: ConfigSyncLocalGateway by lazy {
        ConfigSyncLocalGatewayImpl(
            accountDao = database.accountDao(),
            categoryDao = database.categoryDao(),
            companionDao = database.companionDao(),
            tripTagDao = database.tripTagDao(),
            preferenceDao = database.preferenceDao(),
            ioDispatcher = ioDispatcher
        )
    }

    override val configSheetManager: ConfigSheetManager by lazy {
        ConfigSheetManager(
            googleSheetsClient = googleSheetsClient,
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider,
            ioDispatcher = ioDispatcher
        )
    }

    override val workbookResolver: WorkbookResolver by lazy {
        WorkbookResolver(
            googleDriveClient = googleDriveClient,
            googleSheetsClient = googleSheetsClient,
            preferencesRepository = preferencesRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            syncConfigUseCase = syncConfigUseCase,
            regenerateDashboardUseCase = regenerateDashboardUseCase,
            ioDispatcher = ioDispatcher
        )
    }

    override val transactionRepository: TransactionRepository by lazy {
        TransactionRepositoryImpl(
            transactionDao = database.transactionDao(),
            syncQueueDao = database.syncQueueDao(),
            googleSheetsClient = googleSheetsClient,
            preferencesRepository = preferencesRepository,
            workbookResolver = workbookResolver,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            syncConfigUseCase = syncConfigUseCase,
            calculateSplitUseCase = calculateSplitUseCase,
            nettingUseCase = nettingUseCase,
            voidTransactionUseCase = voidTransactionUseCase,
            accountingSyncExecutor = accountingSyncExecutor,
            ioDispatcher = ioDispatcher
        ).also { impl ->
            impl.onTriggerSync = {
                CoroutineScope(ioDispatcher).launch {
                    syncTransactionsUseCase.invoke()
                }
            }
        }
    }

    override val parseNotificationUseCase: ParseNotificationUseCase by lazy {
        ParseNotificationUseCase(ownPackageName = applicationContext.packageName)
    }

    override val syncTransactionsUseCase: SyncTransactionsUseCase by lazy {
        SyncTransactionsUseCase(transactionRepository = transactionRepository)
    }

    override val syncConfigUseCase: SyncConfigUseCase by lazy {
        SyncConfigUseCase(
            configRepository = configRepository,
            localGateway = configSyncLocalGateway,
            preferencesRepository = preferencesRepository,
            configSheetManager = configSheetManager,
            ioDispatcher = ioDispatcher
        )
    }

    override val configSyncCoordinator: ConfigSyncCoordinator by lazy {
        ConfigSyncCoordinator(
            syncConfigUseCase = syncConfigUseCase,
            syncQueueDao = database.syncQueueDao(),
            localGateway = configSyncLocalGateway,
            applicationScope = CoroutineScope(SupervisorJob() + ioDispatcher),
            ioDispatcher = ioDispatcher
        ).also { coordinator ->
            coordinator.onTriggerQueueSync = {
                CoroutineScope(ioDispatcher).launch {
                    syncTransactionsUseCase.invoke()
                }
            }
        }
    }

    override val regenerateDashboardUseCase: RegenerateDashboardUseCase by lazy {
        RegenerateDashboardUseCase(
            googleSheetsClient = googleSheetsClient,
            preferencesRepository = preferencesRepository,
            accountRepository = accountRepository,
            categoryRepository = categoryRepository,
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider,
            ioDispatcher = ioDispatcher
        )
    }

    override val lineageDao: LineageDao by lazy {
        database.lineageDao()
    }

    override val calculateSplitUseCase: CalculateSplitUseCase by lazy {
        CalculateSplitUseCase()
    }

    override val nettingUseCase: NettingUseCase by lazy {
        NettingUseCase()
    }

    override val settlementWaterfallUseCase: SettlementWaterfallUseCase by lazy {
        SettlementWaterfallUseCase()
    }

    override val repaymentWaterfallUseCase: RepaymentWaterfallUseCase by lazy {
        RepaymentWaterfallUseCase(nettingUseCase = nettingUseCase)
    }

    override val carryForwardUseCase: CarryForwardUseCase by lazy {
        CarryForwardUseCase()
    }

    override val voidTransactionUseCase: VoidTransactionUseCase by lazy {
        VoidTransactionUseCase()
    }

    override val yearCloseUseCase: YearCloseUseCase by lazy {
        YearCloseUseCase()
    }

    override val accountingSyncExecutor: AccountingSyncExecutor by lazy {
        AccountingSyncExecutor(
            googleSheetsClient = googleSheetsClient,
            googleDriveClient = googleDriveClient,
            syncQueueDao = database.syncQueueDao(),
            lineageDao = database.lineageDao(),
            settlementWaterfallUseCase = settlementWaterfallUseCase,
            repaymentWaterfallUseCase = repaymentWaterfallUseCase,
            carryForwardUseCase = carryForwardUseCase,
            nettingUseCase = nettingUseCase,
            yearCloseUseCase = yearCloseUseCase
        )
    }

    override val pendingCaptureDao: PendingCaptureDao by lazy {
        database.pendingCaptureDao()
    }

    override val handledNotificationDao: HandledNotificationDao by lazy {
        database.handledNotificationDao()
    }

    override val foregroundWatcher: ForegroundWatcher by lazy {
        ForegroundWatcher(
            context = applicationContext,
            pendingCaptureDao = pendingCaptureDao
        )
    }
}

/**
 * Factory function to construct an [AppContainer] instance.
 */
fun AppContainer(
    applicationContext: Context,
    tokenProvider: TokenProvider? = null
): AppContainer = DefaultAppContainer(applicationContext, tokenProvider)
