package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
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
    private val budgetRepository: com.financeautomation.domain.repository.BudgetRepository? = null,
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
                val displayNamesByKey = mutableMapOf<String, String>()
                val openItemsByFriend = mutableMapOf<String, Int>()
                for (txn in transactions) {
                    if (txn.status == TransactionStatus.PENDING && txn.owedToMe > 0.0) {
                        val perPersonShares = AccountingUtils.calculateReceivablesForTransaction(txn)
                        for (share in perPersonShares) {
                            val key = share.key
                            receivablesByFriend[key] = (receivablesByFriend[key] ?: 0.0) + share.amount
                            openItemsByFriend[key] = (openItemsByFriend[key] ?: 0) + 1
                            val existing = displayNamesByKey[key]
                            if (existing == null || (existing.none { it.isUpperCase() } && share.displayName.any { it.isUpperCase() })) {
                                displayNamesByKey[key] = share.displayName
                            }
                        }
                    }
                }

                val payablesByFriend = mutableMapOf<String, Double>()
                for (payable in pendingPayables) {
                    val key = AccountingUtils.normalizePersonName(payable.paidBy)
                    payablesByFriend[key] = (payablesByFriend[key] ?: 0.0) + payable.amountIOwe
                    openItemsByFriend[key] = (openItemsByFriend[key] ?: 0) + 1
                }

                val updatedCompanions = companions.map { comp ->
                    val key = AccountingUtils.normalizePersonName(comp.name)
                    val rec = receivablesByFriend[key] ?: 0.0
                    val pay = payablesByFriend[key] ?: 0.0
                    val net = rec - pay
                    comp.copy(balance = net)
                }.toMutableList()

                val allKeys = receivablesByFriend.keys + payablesByFriend.keys
                for (key in allKeys) {
                    if (updatedCompanions.none { AccountingUtils.normalizePersonName(it.name) == key }) {
                        val rec = receivablesByFriend[key] ?: 0.0
                        val pay = payablesByFriend[key] ?: 0.0
                        val net = rec - pay
                        if (kotlin.math.abs(net) > 0.001) {
                            val originalName = pendingPayables.firstOrNull { AccountingUtils.normalizePersonName(it.paidBy) == key }?.paidBy?.trim()
                                ?: displayNamesByKey[key]
                                ?: key
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
            uiState.update { it.copy(defaultAccount = trimmed, userMessage = UiText.res(R.string.settings_msg_default_account_set, trimmed)) }
        }
    }

    fun onAddAccount(name: String, lastFourDigits: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_account_blank)) }
            return
        }

        scope.launch(ioDispatcher) {
            val repo = accountRepository
            if (repo != null) {
                val existing = repo.getAccountByName(trimmed)
                if (existing != null) {
                    uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_account_exists, trimmed)) }
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
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_account, trimmed)) }
            } else {
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_account, trimmed)) }
            }
        }
    }

    fun onArchiveAccount(accountId: String) {
        scope.launch(ioDispatcher) {
            accountRepository?.archiveAccount(accountId)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_account_archived)) }
        }
    }

    fun onAddCategory(name: String, isExcludedFromLivingBurn: Boolean = false) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_category_blank)) }
            return
        }

        scope.launch(ioDispatcher) {
            val repo = categoryRepository
            if (repo != null) {
                val existing = repo.getCategoryByName(trimmed)
                if (existing != null) {
                    uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_category_exists, trimmed)) }
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
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_category, trimmed)) }
            } else {
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_category, trimmed)) }
            }
        }
    }

    fun onToggleCategoryLivingBurnExclusion(categoryId: String, isExcluded: Boolean) {
        scope.launch(ioDispatcher) {
            categoryRepository?.setExcludedFromLivingBurn(categoryId, isExcluded)
            val resId = if (isExcluded) R.string.settings_msg_category_excluded_living_burn else R.string.settings_msg_category_included_living_burn
            uiState.update { it.copy(userMessage = UiText.res(resId)) }
        }
    }

    fun onArchiveCategory(categoryId: String) {
        scope.launch(ioDispatcher) {
            categoryRepository?.archiveCategory(categoryId)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_category_archived)) }
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
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_trip_tag_blank)) }
            return
        }

        scope.launch(ioDispatcher) {
            tripTagRepository?.insertTripTag(trimmed)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_trip_tag, trimmed)) }
        }
    }

    fun onToggleTripTagActive(tagId: String) {
        scope.launch(ioDispatcher) {
            val repo = tripTagRepository ?: return@launch
            val tag = repo.getTagById(tagId) ?: return@launch
            if (tag.isActive) {
                repo.setActiveTag(null)
                preferencesRepository.setActiveTrip(null)
                uiState.update { it.copy(activeTripTag = "", userMessage = UiText.res(R.string.settings_msg_deactivated_trip_tag)) }
            } else {
                repo.setActiveTag(tagId)
                preferencesRepository.setActiveTrip(tag.name)
                uiState.update { it.copy(activeTripTag = tag.name, userMessage = UiText.res(R.string.settings_msg_activated_trip_tag, tag.name)) }
            }
        }
    }

    fun onAddCompanion(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_friend_blank)) }
            return
        }

        scope.launch(ioDispatcher) {
            val existing = peopleRepository.getCompanionByName(trimmed)
            if (existing != null) {
                uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_companion_exists)) }
                return@launch
            }

            val newId = "comp_${UUID.randomUUID().toString().take(8)}"
            peopleRepository.insertCompanion(Companion(id = newId, name = trimmed))
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_companion, trimmed)) }
        }
    }

    fun onRenameCompanion(companionId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_companion_blank)) }
            return
        }

        scope.launch(ioDispatcher) {
            val companion = peopleRepository.getCompanionById(companionId)
            val oldName = companion?.name
            peopleRepository.updateCompanionName(companionId, trimmed)
            if (oldName != null && !oldName.equals(trimmed, ignoreCase = true)) {
                transactionRepository?.updateCompanionName(oldName, trimmed)
                resolvePayableDao()?.updatePaidByName(oldName, trimmed)
            }
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_renamed_companion, trimmed)) }
        }
    }

    fun onRenameAccount(accountId: String, newName: String, lastFourDigits: String? = null) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_account_blank)) }
            return
        }
        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            val acc = repo.getAccountById(accountId) ?: return@launch
            val oldName = acc.name
            repo.updateAccount(acc.copy(name = trimmed, lastFourDigits = lastFourDigits?.trim()?.ifEmpty { null }))
            if (!oldName.equals(trimmed, ignoreCase = true)) {
                transactionRepository?.updateAccountName(oldName, trimmed)
            }
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_renamed_account, trimmed)) }
        }
    }

    fun onRenameCategory(categoryId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) {
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_category_blank)) }
            return
        }
        scope.launch(ioDispatcher) {
            val repo = categoryRepository ?: return@launch
            val cat = repo.getCategoryById(categoryId) ?: return@launch
            val oldName = cat.name
            repo.updateCategory(cat.copy(name = trimmed))
            if (!oldName.equals(trimmed, ignoreCase = true)) {
                transactionRepository?.updateCategoryName(oldName, trimmed)
                budgetRepository?.renameCategory(oldName, trimmed)
            }
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_renamed_category, trimmed)) }
        }
    }

    /** Removes an ended trip from the list; refused while it is the active trip. */
    fun onRemoveTripTag(name: String) {
        scope.launch(ioDispatcher) {
            val repo = tripTagRepository ?: return@launch
            when (val result = repo.removeTripTag(name)) {
                is com.financeautomation.core.model.Result.Success ->
                    uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_trip_removed)) }
                is com.financeautomation.core.model.Result.Error ->
                    uiState.update {
                        it.copy(errorMessage = result.message?.let { m -> UiText.dynamic(m) }
                            ?: UiText.res(R.string.settings_err_trip_remove_failed))
                    }
                else -> Unit
            }
        }
    }

    fun onRemoveCompanion(companionId: String) {
        val companion = uiState.value.companions.find { it.id == companionId || it.name.equals(companionId, ignoreCase = true) }
        val balance = companion?.balance ?: 0.0
        val openCount = companion?.let { uiState.value.companionOpenItemCounts[it.name.trim().lowercase()] ?: 0 } ?: 0
        if (kotlin.math.abs(balance) > 0.001 || openCount > 0) {
            val friendName = companion?.name ?: "friend"
            uiState.update { it.copy(errorMessage = UiText.res(R.string.settings_err_cannot_remove_open_balance, friendName)) }
            return
        }
        scope.launch(ioDispatcher) {
            val targetName = companion?.name ?: companionId
            when (val result = peopleRepository.removePerson(targetName)) {
                is com.financeautomation.core.model.Result.Success ->
                    uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_companion_removed)) }
                is com.financeautomation.core.model.Result.Error ->
                    uiState.update { it.copy(errorMessage = result.message?.let { m -> UiText.dynamic(m) }
                        ?: UiText.res(R.string.settings_err_cannot_remove_open_balance, targetName)) }
                else -> Unit
            }
        }
    }
}

