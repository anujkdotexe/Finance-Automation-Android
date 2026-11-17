package com.financeautomation.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.financeautomation.R
import com.financeautomation.core.utils.AmountInput
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.domain.usecase.AmountOutcome
import com.financeautomation.domain.usecase.ExpressionEvaluator

/** Smallest the hero text may shrink to before it is left to clip. */
private const val MIN_TEXT_SCALE = 0.4f
private const val SHRINK_STEP = 0.9f

/** One step of the shrink-before-overflow rule: smaller while the text overflows, never below the minimum. */
fun nextAmountTextScale(current: Float, overflows: Boolean): Float =
    if (overflows && current > MIN_TEXT_SCALE) maxOf(MIN_TEXT_SCALE, current * SHRINK_STEP) else current

/** The error shown under an amount field for [outcome], or null when there is none. */
fun amountErrorRes(outcome: AmountOutcome, zeroIsFine: Boolean): Int? = when (outcome) {
    AmountOutcome.Unreadable -> R.string.amount_err_unreadable
    AmountOutcome.TooLarge -> R.string.amount_err_too_large
    AmountOutcome.NotPositive -> if (zeroIsFine) null else R.string.amount_err_zero
    else -> null
}

private val EXPRESSION_CHARS = "+-*/()"

/**
 * The one amount box of the app. Every edit and every paste goes through [AmountInput.clean] before it reaches
 * [onValueChange], so callers only ever hold clean text. A readable expression shows its result live, an unreadable
 * one shows an error, and the caller keeps Save disabled by checking [ExpressionEvaluator.evaluateDetailed] on the
 * same text. [hero] is the large centred box of the sheets; otherwise a normal outlined field.
 */
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    enabled: Boolean = true,
    /** True when a result of zero is acceptable, as for the custom share where a friend may pay it all. */
    zeroIsFine: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val outcome = remember(value) { ExpressionEvaluator().evaluateDetailed(value) }
    val errorRes = amountErrorRes(outcome, zeroIsFine)
    val errorText = errorRes?.let { stringResource(it) }
    val description = stringResource(R.string.amount_field_description)
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }

    val fieldModifier = Modifier
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged {
            focused = it.isFocused
            onFocusChanged(it.isFocused)
        }
        .semantics {
            contentDescription = description
            if (errorText != null) error(errorText)
        }
    val keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done)
    val keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (hero) Alignment.CenterHorizontally else Alignment.Start
    ) {
        if (hero) {
            var scale by remember(value) { mutableFloatStateOf(1f) }
            val baseStyle = FinanceTheme.typography.amountHero
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.currency_symbol_inr),
                    style = baseStyle,
                    color = FinanceTheme.colors.primary,
                    modifier = Modifier.padding(end = FinanceTheme.spacing.xs)
                )
                BasicTextField(
                    value = value,
                    onValueChange = { onValueChange(AmountInput.clean(it)) },
                    enabled = enabled,
                    singleLine = true,
                    textStyle = baseStyle.copy(
                        fontSize = baseStyle.fontSize * scale,
                        color = FinanceTheme.colors.onSurface,
                        textAlign = TextAlign.Center
                    ),
                    cursorBrush = SolidColor(FinanceTheme.colors.primary),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    onTextLayout = { scale = nextAmountTextScale(scale, it.didOverflowWidth) },
                    modifier = Modifier
                        .weight(1f)
                        .then(fieldModifier),
                    decorationBox = { inner ->
                        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                            if (value.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.amount_placeholder),
                                    style = baseStyle.copy(fontSize = baseStyle.fontSize * scale),
                                    color = FinanceTheme.colors.outlineVariant
                                )
                            }
                            inner()
                        }
                    }
                )
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(AmountInput.clean(it)) },
                enabled = enabled,
                singleLine = true,
                isError = errorRes != null,
                placeholder = { Text(stringResource(R.string.amount_placeholder)) },
                shape = FinanceShapes.medium,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth().then(fieldModifier)
            )
        }

        val resultText = (outcome as? AmountOutcome.Value)
            ?.takeIf { value.any { c -> c in EXPRESSION_CHARS } }
            ?.let { stringResource(R.string.amount_preview, formatRupeesAuto(it.amount)) }
        if (errorText != null) {
            Text(
                text = errorText,
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = FinanceTheme.spacing.xxs)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        } else if (resultText != null) {
            Text(
                text = resultText,
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = FinanceTheme.spacing.xxs)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }

        if (focused && enabled) {
            AmountOperatorRow(onInsert = { onValueChange(AmountInput.clean(value + it)) })
        }
    }
}

/** Calculator keys the decimal keyboard does not have. */
@Composable
private fun AmountOperatorRow(onInsert: (String) -> Unit) {
    val keys = listOf(
        "+" to R.string.amount_key_plus,
        "-" to R.string.amount_key_minus,
        "*" to R.string.amount_key_times,
        "/" to R.string.amount_key_divide,
        "(" to R.string.amount_key_open,
        ")" to R.string.amount_key_close
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for ((symbol, labelRes) in keys) {
            val label = stringResource(labelRes)
            TextButton(
                onClick = { onInsert(symbol) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = label }
            ) {
                Text(text = symbol, style = FinanceTheme.typography.titleMedium)
            }
        }
    }
}
