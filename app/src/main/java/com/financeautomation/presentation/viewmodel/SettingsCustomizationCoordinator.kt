package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.TransactionStatus
import com.financeautomation.data.local.dao.PayableDao
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.Transaction
import com.financeautomation.domain.model.TripTag
import com.financeautomation.domain.repository.AccountRepository
import com.financeautomation.domain.repository.CategoryRepository
import com.financeautomation.domain.repository.PeopleRepository
import com.financeautomation.domain.repository.PreferencesRepository
import com.financeautomation.domain.repository.TransactionRepository
import com.financeautomation.domain.repository.TripTagRepository
import com.financeautomation.domain.utils.AccountingUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Coordinator managing customization entities: Accounts, Categories, Trip Tags, and Companions (Phase 10).
 */
class SettingsCustomizationCoordinator(
    private val preferencesRepository: PreferencesRepository,
    private val peopleRepository: PeopleRepository,
    private val transactionRepository: TransactionRepository? = null,
    private val payableDao: PayableDao? = null,
    private val payableDaoProvider: (suspend () -> PayableDao)? = null,
    private val accountRepository: AccountRepository?,
    private val categoryRepository: CategoryRepository?,
    private val tripTagRepository: TripTagRepository?,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<SettingsUiState>,
    private val ioDispatcher: CoroutineDispatcher
) {

    private suspend fun resolvePayableDao(): PayableDao? {
        return payableDao ?: payableDaoProvider?.invoke()
    }

    fun observeEntities() {
        scope.launch(ioDispatcher) {
            val resolvedPayableDao = resolvePayableDao()
            val payablesFlow = resolvedPayableDao?.observePending() ?: flowOf(emptyList())
            val transactionsFlow = transactionRepository?.getTransactions() ?: flowOf(emptyList())

            combine(
                peopleRepository.getCompanions(),
                transactionsFlow,
                payablesFlow
            ) { companions, transactions, pendingPayables ->
                val receivablesByFriend = mutableMapOf<String, Double>()
                val openItemsByFriend = mutableMapOf<String, Int>()
                for (txn in transactions) {
                    if (txn.status == TransactionStatus.PENDING && txn.owedToMe > 0.0) {
                        val tokens = AccountingUtils.tokens(txn.companions)
                        if (tokens.isNotEmpty() && !txn.companions.equals("Personal", ignoreCase = true)) {
                            val sharePerPerson = txn.owedToMe / tokens.size
                            for (friend in tokens) {
                                val key = friend.trim().lowercase()
                                receivablesByFriend[key] = (receivablesByFriend[key] ?: 0.0) + sharePerPerson
                                openItemsByFriend[key] = (openItemsByFriend[key] ?: 0) + 1
                            }
                        }
                    }
                }

                val payablesByFriend = mutableMapOf<String, Double>()
                for (payable in pendingPayables) {
                    val key = payable.paidBy.trim().lowercase()
                    payablesByFriend[key] = (payablesByFriend[key] ?: 0.0) + payable.amountIOwe
                    openItemsByFriend[key] = (openItemsByFriend[key] ?: 0) + 1
                }

                val updatedCompanions = companions.map { comp ->
                    val key = comp.name.trim().lowercase()
                    val rec = receivablesByFriend[key] ?: 0.0
                    val pay = payablesByFriend[key] ?: 0.0
                    val net = rec - pay
                    comp.copy(balance = net)
                }.toMutableList()

                val allKeys = receivablesByFriend.keys + payablesByFriend.keys
                for (key in allKeys) {
                    if (updatedCompanions.none { it.name.trim().lowercase() == key }) {
                        val rec = receivablesByFriend[key] ?: 0.0
                        val pay = payablesByFriend[key] ?: 0.0
                        val net = rec - pay
                        if (kotlin.math.abs(net) > 0.001) {
                            val originalName = pendingPayables.firstOrNull { it.paidBy.trim().lowercase() == key }?.paidBy?.trim()
                                ?: key.replaceFirstChar { it.uppercase() }
                            updatedCompanions.add(
                                Companion(
                                    id = "friend_$key",
                                    name = originalName,
                                    balance = net
                                )
                            )
                        }
                    }
                }

                updatedCompanions to openItemsByFriend
            }.collect { (list, counts) ->
                uiState.update { it.copy(companions = list, companionOpenItemCounts = counts) }
            }
        }
        accountRepository?.let { repo ->
            scope.launch(ioDispatcher) {
                repo.getAccounts(includeArchived = false).collect { list ->
                    uiState.update { it.copy(accounts = list) }
                }
            }
        }
        categoryRepository?.let { repo ->
            scope.launch(ioDispatcher) {
                repo.getCategories(includeArchived = false).collect { list ->
                    uiState.update { it.copy(categories = list) }
                }
            }
        }
        tripTagRepository?.let { repo ->
            scope.launch(ioDispatcher) {
                repo.getTripTags(includeArchived = false).collect { list ->
                    uiState.update { it.copy(tripTags = list) }
                }
            }
        }
    }

    fun onDefaultAccountChanged(account: String) {
        scope.launch(ioDispatcher) {
            val trimmed = account.trim()
            preferencesRepository.setDefaultAccount(trimmed)
            accountRepository?.let { repo ->
                val match = repo.getAccountByName(trimmed)
                if (match != null) {
                    repo.setDefaultAccount(match.id)
                }
            }
            uiState.update { it.copy(defaultAccount = trimmed, userMessage = "Default account set to $trimmed") }
        }
    }

    fun onAddAccount(name: String, lastFourDigits: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = "Account name cannot be blank") }
            return
        }

        scope.launch(ioDispatcher) {
            val repo = accountRepository
            if (repo != null) {
                val existing = repo.getAccountByName(trimmed)
                if (existing != null) {
                    uiState.update { it.copy(errorMessage = "Account '$trimmed' already exists") }
                    return@launch
                }
                val newAcc = Account(
                    id = "acc_${UUID.randomUUID().toString().take(8)}",
                    name = trimmed,
                    lastFourDigits = lastFourDigits?.trim()?.ifEmpty { null },
                    isDefault = false,
                    isArchived = false,
                    displayOrder = uiState.value.accounts.size
                )
                repo.insertAccount(newAcc)
                uiState.update { it.copy(userMessage = "Added account $trimmed") }
            } else {
                uiState.update { it.copy(userMessage = "Added account $trimmed") }
            }
        }
    }

    fun onArchiveAccount(accountId: String) {
        scope.launch(ioDispatcher) {
            accountRepository?.archiveAccount(accountId)
            uiState.update { it.copy(userMessage = "Account archived") }
        }
    }

    fun onAddCategory(name: String, isExcludedFromLivingBurn: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = "Category name cannot be blank") }
            return
        }

        scope.launch(ioDispatcher) {
            val repo = categoryRepository
            if (repo != null) {
                val existing = repo.getCategoryByName(trimmed)
                if (existing != null) {
                    uiState.update { it.copy(errorMessage = "Category '$trimmed' already exists") }
                    return@launch
                }
                val newCat = Category(
                    id = "cat_${UUID.randomUUID().toString().take(8)}",
                    name = trimmed,
                    isDefault = false,
                    isExcludedFromLivingBurn = isExcludedFromLivingBurn,
                    isArchived = false,
                    displayOrder = uiState.value.categories.size
                )
                repo.insertCategory(newCat)
                uiState.update { it.copy(userMessage = "Added category $trimmed") }
            } else {
                uiState.update { it.copy(userMessage = "Added category $trimmed") }
            }
        }
    }

    fun onToggleCategoryLivingBurnExclusion(categoryId: String, isExcluded: Boolean) {
        scope.launch(ioDispatcher) {
            categoryRepository?.setExcludedFromLivingBurn(categoryId, isExcluded)
            val msg = if (isExcluded) "Category excluded from living burn" else "Category included in living burn"
            uiState.update { it.copy(userMessage = msg) }
        }
    }

    fun onArchiveCategory(categoryId: String) {
        scope.launch(ioDispatcher) {
            categoryRepository?.archiveCategory(categoryId)
            uiState.update { it.copy(userMessage = "Category archived") }
        }
    }

    fun onActiveTripTagChanged(tag: String) {
        scope.launch(ioDispatcher) {
            val trimmed = tag.trim()
            val finalTag = if (trimmed.isEmpty()) null else trimmed
            preferencesRepository.setActiveTrip(finalTag)
            tripTagRepository?.let { repo ->
                if (finalTag != null) {
                    val match = repo.getTagByName(finalTag)
                    repo.setActiveTag(match?.id)
                } else {
                    repo.setActiveTag(null)
                }
            }
            uiState.update { it.copy(activeTripTag = trimmed) }
        }
    }

    fun onAddTripTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = "Trip tag cannot be blank") }
            return
        }

        scope.launch(ioDispatcher) {
            tripTagRepository?.insertTripTag(trimmed)
            uiState.update { it.copy(userMessage = "Added trip tag $trimmed") }
        }
    }

    fun onToggleTripTagActive(tagId: String) {
        scope.launch(ioDispatcher) {
            val repo = tripTagRepository ?: return@launch
            val tag = repo.getTagById(tagId) ?: return@launch
            if (tag.isActive) {
                repo.setActiveTag(null)
                preferencesRepository.setActiveTrip(null)
                uiState.update { it.copy(activeTripTag = "", userMessage = "Deactivated trip tag") }
            } else {
                repo.setActiveTag(tagId)
                preferencesRepository.setActiveTrip(tag.name)
                uiState.update { it.copy(activeTripTag = tag.name, userMessage = "Activated trip tag '${tag.name}'") }
            }
        }
    }

    fun onAddCompanion(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = "Friend name cannot be blank") }
            return
        }

        scope.launch(ioDispatcher) {
            val existing = peopleRepository.getCompanionByName(trimmed)
            if (existing != null) {
                uiState.update { it.copy(errorMessage = "A companion with this name already exists") }
                return@launch
            }

            val newId = "comp_${UUID.randomUUID().toString().take(8)}"
            peopleRepository.insertCompanion(Companion(id = newId, name = trimmed))
            uiState.update { it.copy(userMessage = "Added companion $trimmed") }
        }
    }

    fun onRenameCompanion(companionId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = "Companion name cannot be blank") }
            return
        }

        scope.launch(ioDispatcher) {
            peopleRepository.updateCompanionName(companionId, trimmed)
            uiState.update { it.copy(userMessage = "Renamed companion to $trimmed") }
        }
    }

    fun onRemoveCompanion(companionId: String) {
        scope.launch(ioDispatcher) {
            peopleRepository.deleteCompanionById(companionId)
            uiState.update { it.copy(userMessage = "Companion removed") }
        }
    }
}

