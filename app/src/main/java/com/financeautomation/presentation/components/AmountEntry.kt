package com.financeautomation.presentation.components

import com.financeautomation.core.utils.AmountInput
import com.financeautomation.domain.usecase.AmountOutcome
import com.financeautomation.domain.usecase.ExpressionEvaluator

/**
 * The state of one amount box for the sheets that keep their amount as local text (Friend paid, Repaid a friend,
 * Income return, Add subscription). Text only ever enters through [onInput], so it is always clean, and Save is
 * allowed only for a readable amount above zero.
 */
data class AmountEntry(val text: String = "") {

    val outcome: AmountOutcome get() = EVALUATOR.evaluateDetailed(text)

    /** True only for a readable amount above zero: Empty, Incomplete, Unreadable, TooLarge and NotPositive are all false. */
    val canSave: Boolean get() = outcome is AmountOutcome.Value

    /** The amount to save, or 0.0 when [canSave] is false. Never used to save without checking [canSave]. */
    val amountOrZero: Double get() = (outcome as? AmountOutcome.Value)?.amount ?: 0.0

    fun onInput(raw: String): AmountEntry = AmountEntry(AmountInput.clean(raw))

    private companion object {
        val EVALUATOR = ExpressionEvaluator()
    }
}
