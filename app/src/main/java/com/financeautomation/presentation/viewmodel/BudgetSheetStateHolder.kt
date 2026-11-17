package com.financeautomation.presentation.viewmodel

import com.financeautomation.core.model.Result
import com.financeautomation.core.utils.AmountInput
import com.financeautomation.domain.usecase.AmountOutcome
import com.financeautomation.domain.repository.BudgetRepository
import com.financeautomation.domain.usecase.ExpressionEvaluator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BudgetSheetState(
    /** Text in the amount field; may be an expression such as 1,200+300. */
    val input: String = "",
    /** The live result of the expression, or null while the text is empty or not a valid positive amount. */
    val preview: Double? = null,
    /** True when the text is an unreadable, too large or not positive amount (an unfinished sum is not an error yet). */
    val showInvalid: Boolean = false,
    /** True when the category already has a budget, which enables "Clear budget". */
    val hasExisting: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
    /** Set once a save or clear went through; the sheet closes. */
    val finished: Boolean = false
) {
    val canSave: Boolean get() = preview != null && !isSaving
}

/**
 * State of the "Monthly budget for <category>" sheet. The amount field accepts simple expressions through
 * [ExpressionEvaluator] and shows the result as the owner types. Invalid or zero amounts are never saved.
 */
class BudgetSheetStateHolder(
    val category: String,
    existingAmount: Double?,
    private val repository: BudgetRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val evaluator: ExpressionEvaluator = ExpressionEvaluator()
) {
    private val _state = MutableStateFlow(BudgetSheetState(hasExisting = existingAmount != null))
    val state: StateFlow<BudgetSheetState> = _state.asStateFlow()

    init {
        if (existingAmount != null && existingAmount > 0.0) onInputChanged(plainAmount(existingAmount))
    }

    fun onInputChanged(raw: String) {
        val text = AmountInput.clean(raw)
        val outcome = evaluator.evaluateDetailed(text)
        val value = (outcome as? AmountOutcome.Value)?.amount
        _state.update {
            it.copy(
                input = text,
                preview = value,
                showInvalid = outcome.isError,
                saveFailed = false
            )
        }
    }

    /** Saves the budget. Does nothing, and flags the field, when the amount is missing or invalid. */
    fun save() {
        val amount = _state.value.preview
        if (amount == null) {
            _state.update { it.copy(showInvalid = true) }
            return
        }
        if (_state.value.isSaving) return
        _state.update { it.copy(isSaving = true, saveFailed = false) }
        scope.launch(ioDispatcher) {
            when (repository.setBudget(category, amount)) {
                is Result.Success -> _state.update { it.copy(isSaving = false, finished = true) }
                else -> _state.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    /** Removes the budget of the category. Does nothing when there is none. */
    fun clear() {
        if (!_state.value.hasExisting || _state.value.isSaving) return
        _state.update { it.copy(isSaving = true, saveFailed = false) }
        scope.launch(ioDispatcher) {
            when (repository.removeBudget(category)) {
                is Result.Success -> _state.update { it.copy(isSaving = false, hasExisting = false, finished = true) }
                else -> _state.update { it.copy(isSaving = false, saveFailed = true) }
            }
        }
    }

    private fun plainAmount(amount: Double): String =
        java.math.BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()
}
