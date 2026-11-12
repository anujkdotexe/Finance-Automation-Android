package com.financeautomation.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.financeautomation.data.local.dao.CategoryStatDao
import com.financeautomation.data.local.dao.PendingCaptureDao
import com.financeautomation.data.local.dao.SyncQueueDao
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.usecase.CalculateSplitUseCase
import com.financeautomation.domain.usecase.LogExpenseUseCase
import com.financeautomation.domain.usecase.PredictCategoryUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Factory class for constructing QuickLogViewModel instances.
 */
class QuickLogViewModelFactory(
    private val transactionRepository: TransactionRepository,
    private val peopleRepository: PeopleRepository,
    private val categoryRepository: CategoryRepository,
    private val accountRepository: AccountRepository,
    private val tripTagRepository: TripTagRepository,
    private val pendingCaptureDaoProvider: suspend () -> PendingCaptureDao,
    private val syncQueueDaoProvider: suspend () -> SyncQueueDao,
    private val categoryStatDaoProvider: (suspend () -> CategoryStatDao)? = null,
    private val predictCategoryUseCase: PredictCategoryUseCase = PredictCategoryUseCase(),
    private val calculateSplitUseCase: CalculateSplitUseCase = CalculateSplitUseCase(),
    private val logExpenseUseCase: LogExpenseUseCase = LogExpenseUseCase(transactionRepository, calculateSplitUseCase),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val subscriptionRepository: com.financeautomation.domain.repository.SubscriptionRepository? = null
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return QuickLogViewModel(
            transactionRepository = transactionRepository,
            peopleRepository = peopleRepository,
            categoryRepository = categoryRepository,
            accountRepository = accountRepository,
            tripTagRepository = tripTagRepository,
            pendingCaptureDaoProvider = pendingCaptureDaoProvider,
            syncQueueDaoProvider = syncQueueDaoProvider,
            categoryStatDaoProvider = categoryStatDaoProvider,
            predictCategoryUseCase = predictCategoryUseCase,
            calculateSplitUseCase = calculateSplitUseCase,
            logExpenseUseCase = logExpenseUseCase,
            ioDispatcher = ioDispatcher,
            subscriptionRepository = subscriptionRepository
        ) as T
    }
}

