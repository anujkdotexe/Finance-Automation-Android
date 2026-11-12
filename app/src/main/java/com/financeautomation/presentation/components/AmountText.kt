package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

enum class AmountTone {
    DEFAULT,
    MONEY_OUT,
    MONEY_IN,
    MONEY_OWED,
    INFO
}

/**
 * Renders monetary figures strictly with tabular figures and theme color tokens (Design Guide 4).
 * Defaults to formatRupeesAuto (shows paise only when not 00).
 */
@Composable
fun AmountText(
    amount: Double,
    modifier: Modifier = Modifier,
    tone: AmountTone = AmountTone.DEFAULT,
    style: TextStyle = FinanceTheme.typography.amountLarge,
    includeDecimals: Boolean? = null,
    prefix: String = ""
) {
    val color: Color = when (tone) {
        AmountTone.DEFAULT -> FinanceTheme.colors.onSurface
        AmountTone.MONEY_OUT -> FinanceTheme.colors.moneyOut
        AmountTone.MONEY_IN -> FinanceTheme.colors.moneyIn
        AmountTone.MONEY_OWED -> FinanceTheme.colors.moneyOwed
        AmountTone.INFO -> FinanceTheme.colors.info
    }

    val formatted = if (includeDecimals == null) {
        formatRupeesAuto(amount)
    } else {
        formatRupees(amount, includeDecimals)
    }
    val displayText = if (prefix.isNotBlank() && !formatted.startsWith("-")) "$prefix$formatted" else formatted

    Text(
        text = displayText,
        style = style,
        color = color,
        modifier = modifier
    )
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun AmountTextPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        AmountText(amount = 2450.50, tone = AmountTone.MONEY_OUT, includeDecimals = true)
    }
}

@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AmountTextPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        AmountText(amount = 2450.50, tone = AmountTone.MONEY_IN, includeDecimals = false)
    }
}

@Preview(name = "Large Font", showBackground = true, fontScale = 1.5f)
@Composable
private fun AmountTextPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        AmountText(amount = 125000.0, tone = AmountTone.MONEY_OWED, includeDecimals = false)
    }
}
