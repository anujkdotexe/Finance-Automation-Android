package com.financeautomation.presentation.viewmodel

import com.financeautomation.R
import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.core.utils.Diagnostics
import com.financeautomation.core.utils.PrivacyScrubber
import com.financeautomation.core.utils.SyncErrorKind
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

    private fun mapInputError(error: com.financeautomation.core.utils.InputPolicy.InputError, limit: Int?): UiText {
        return when (error) {
            com.financeautomation.core.utils.InputPolicy.InputError.BLANK -> UiText.res(R.string.input_err_blank)
            com.financeautomation.core.utils.InputPolicy.InputError.TOO_LONG -> UiText.res(R.string.input_err_too_long, limit ?: com.financeautomation.core.utils.InputPolicy.MAX_NAME_LENGTH)
            com.financeautomation.core.utils.InputPolicy.InputError.INVALID_CHARS -> UiText.res(R.string.input_err_invalid_chars)
            com.financeautomation.core.utils.InputPolicy.InputError.RESERVED_NAME -> UiText.res(R.string.input_err_reserved)
            com.financeautomation.core.utils.InputPolicy.InputError.DUPLICATE_NAME -> UiText.res(R.string.input_err_duplicate)
            else -> UiText.res(R.string.input_err_invalid_chars)
        }
    }

    fun onAddAccount(name: String, lastFourDigits: String? = null) {
        val existing = uiState.value.accounts.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(name, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue
        val cleanDigits = lastFourDigits?.let { com.financeautomation.core.utils.InputPolicy.cleanDigits4(it) }?.ifEmpty { null }

        scope.launch(ioDispatcher) {
            val repo = accountRepository
            if (repo != null) {
                val newAcc = Account(
                    id = "acc_${UUID.randomUUID().toString().take(8)}",
                    name = cleanName,
                    lastFourDigits = cleanDigits,
                    isDefault = false,
                    isArchived = false,
                    displayOrder = uiState.value.accounts.size
                )
                repo.insertAccount(newAcc)
                com.financeautomation.core.utils.AnalyticsManager.logEvent(
                    eventName = com.financeautomation.core.utils.UsageEvents.EVENT_FEATURE_USED,
                    params = mapOf("feature_name" to "custom_account_added")
                )
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_account, cleanName)) }
            } else {
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_account, cleanName)) }
            }
        }
    }

    fun onArchiveAccount(accountId: String) {
        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            val acc = repo.getAccountById(accountId) ?: return@launch
            if (CategoryConstants.isProtectedAccount(acc.name)) {
                uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in account ${acc.name} cannot be archived.")) }
                return@launch
            }
            repo.archiveAccount(accountId)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_account_archived)) }
        }
    }

    fun onDeleteAccount(accountId: String) {
        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            val acc = repo.getAccountById(accountId) ?: return@launch
            if (CategoryConstants.isProtectedAccount(acc.name)) {
                uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in account ${acc.name} cannot be deleted.")) }
                return@launch
            }
            repo.deleteAccount(accountId)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_account_deleted)) }
        }
    }

    fun onReorderAccounts(accountIds: List<String>) {
        scope.launch(ioDispatcher) {
            accountRepository?.reorderAccounts(accountIds)
        }
    }

    fun onAddCategory(name: String, isExcludedFromLivingBurn: Boolean = false, colorName: String = "default_tag") {
        val existing = uiState.value.categories.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(name, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue

        scope.launch(ioDispatcher) {
            val repo = categoryRepository
            if (repo != null) {
                val newCat = Category(
                    id = "cat_${UUID.randomUUID().toString().take(8)}",
                    name = cleanName,
                    isDefault = false,
                    isExcludedFromLivingBurn = isExcludedFromLivingBurn,
                    isArchived = false,
                    displayOrder = uiState.value.categories.size,
                    iconName = colorName
                )
                repo.insertCategory(newCat)
                com.financeautomation.core.utils.AnalyticsManager.logEvent(
                    eventName = com.financeautomation.core.utils.UsageEvents.EVENT_FEATURE_USED,
                    params = mapOf("feature_name" to "custom_category_added")
                )
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_category, cleanName)) }
            } else {
                uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_category, cleanName)) }
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
            val repo = categoryRepository ?: return@launch
            val cat = repo.getCategoryById(categoryId) ?: return@launch
            if (CategoryConstants.isProtectedCategory(cat.name)) {
                uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in category ${cat.name} cannot be hidden or archived.")) }
                return@launch
            }
            repo.archiveCategory(categoryId)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_category_archived)) }
        }
    }

    fun onReorderCategories(categoryIds: List<String>) {
        scope.launch(ioDispatcher) {
            categoryRepository?.reorderCategories(categoryIds)
        }
    }

    fun onUpdateCategoryColor(categoryId: String, colorName: String) {
        scope.launch(ioDispatcher) {
            val repo = categoryRepository ?: return@launch
            val cat = repo.getCategoryById(categoryId) ?: return@launch
            repo.updateCategory(cat.copy(iconName = colorName))
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
        val existing = uiState.value.tripTags.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(name, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue

        scope.launch(ioDispatcher) {
            tripTagRepository?.insertTripTag(cleanName)
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_trip_tag, cleanName)) }
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
        val existing = uiState.value.companions.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(name, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue

        scope.launch(ioDispatcher) {
            val newId = "comp_${UUID.randomUUID().toString().take(8)}"
            peopleRepository.insertCompanion(Companion(id = newId, name = cleanName))
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_added_companion, cleanName)) }
        }
    }

    fun onRenameCompanion(companionId: String, newName: String) {
        val existing = uiState.value.companions.filter { it.id != companionId }.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(newName, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue

        scope.launch(ioDispatcher) {
            val companion = peopleRepository.getCompanionById(companionId)
            val oldName = companion?.name
            peopleRepository.updateCompanionName(companionId, cleanName)
            if (oldName != null && !oldName.equals(cleanName, ignoreCase = true)) {
                transactionRepository?.updateCompanionName(oldName, cleanName)
                resolvePayableDao()?.updatePaidByName(oldName, cleanName)
            }
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_renamed_companion, cleanName)) }
        }
    }

    fun onRenameAccount(accountId: String, newName: String, lastFourDigits: String? = null) {
        val current = uiState.value.accounts.firstOrNull { it.id == accountId }
        if (current != null && CategoryConstants.isProtectedAccount(current.name)) {
            uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in account ${current.name} cannot be renamed.")) }
            return
        }
        val existing = uiState.value.accounts.filter { it.id != accountId }.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(newName, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue
        val cleanDigits = lastFourDigits?.let { com.financeautomation.core.utils.InputPolicy.cleanDigits4(it) }?.ifEmpty { null }

        scope.launch(ioDispatcher) {
            val repo = accountRepository ?: return@launch
            val acc = repo.getAccountById(accountId) ?: return@launch
            val oldName = acc.name
            if (CategoryConstants.isProtectedAccount(oldName)) {
                uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in account $oldName cannot be renamed.")) }
                return@launch
            }
            repo.updateAccount(acc.copy(name = cleanName, lastFourDigits = cleanDigits))
            if (!oldName.equals(cleanName, ignoreCase = true)) {
                transactionRepository?.updateAccountName(oldName, cleanName)
            }
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_renamed_account, cleanName)) }
        }
    }

    fun onRenameCategory(categoryId: String, newName: String) {
        val current = uiState.value.categories.firstOrNull { it.id == categoryId }
        if (current != null && CategoryConstants.isProtectedCategory(current.name)) {
            uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in category ${current.name} cannot be renamed.")) }
            return
        }
        val existing = uiState.value.categories.filter { it.id != categoryId }.map { it.name }
        val validation = com.financeautomation.core.utils.InputPolicy.validateName(newName, existing)
        if (!validation.isValid) {
            val err = validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Error
            uiState.update { it.copy(errorMessage = mapInputError(err.reason, err.limit)) }
            return
        }
        val cleanName = (validation as com.financeautomation.core.utils.InputPolicy.ValidationResult.Valid).cleanValue
        scope.launch(ioDispatcher) {
            val repo = categoryRepository ?: return@launch
            val cat = repo.getCategoryById(categoryId) ?: return@launch
            val oldName = cat.name
            if (CategoryConstants.isProtectedCategory(oldName)) {
                uiState.update { it.copy(errorMessage = UiText.dynamic("Built-in category $oldName cannot be renamed.")) }
                return@launch
            }
            repo.updateCategory(cat.copy(name = cleanName))
            if (!oldName.equals(cleanName, ignoreCase = true)) {
                transactionRepository?.updateCategoryName(oldName, cleanName)
                budgetRepository?.renameCategory(oldName, cleanName)
            }
            uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_renamed_category, cleanName)) }
        }
    }

    /** Removes an ended trip from the list; refused while it is the active trip. */
    fun onRemoveTripTag(name: String) {
        scope.launch(ioDispatcher) {
            val repo = tripTagRepository ?: return@launch
            when (val result = repo.removeTripTag(name)) {
                is com.financeautomation.core.model.Result.Success ->
                    uiState.update { it.copy(userMessage = UiText.res(R.string.settings_msg_trip_removed)) }
                is com.financeautomation.core.model.Result.Error -> {
                    Diagnostics.record("SettingsCustomizationCoordinator", "removeTripTag: ${SyncErrorKind.safeCode("remove trip", result.throwable)} ${PrivacyScrubber.scrub(result.message.orEmpty())}")
                    uiState.update {
                        it.copy(errorMessage = UiText.dynamic(SyncErrorKind.formatUserMessage("remove trip", result.throwable)))
                    }
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
                is com.financeautomation.core.model.Result.Error -> {
                    Diagnostics.record("SettingsCustomizationCoordinator", "removeCompanion: ${SyncErrorKind.safeCode("remove companion", result.throwable)} ${PrivacyScrubber.scrub(result.message.orEmpty())}")
                    uiState.update {
                        it.copy(errorMessage = UiText.dynamic(SyncErrorKind.formatUserMessage("remove companion", result.throwable)))
                    }
                }
                else -> Unit
            }
        }
    }
}

