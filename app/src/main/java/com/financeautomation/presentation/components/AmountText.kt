package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
 * Monetary figure rendered with monospaced tabular numerals.
 * Never wraps, reserves width, defaults to formatRupeesAuto.
 */
@Composable
fun AmountText(
    amount: Double,
    modifier: Modifier = Modifier,
    tone: AmountTone = AmountTone.DEFAULT,
    style: TextStyle = FinanceTheme.typography.kpiMedium,
    includeDecimals: Boolean? = null,
    prefix: String = ""
) {
    val color: Color = when (tone) {
        AmountTone.DEFAULT -> FinanceTheme.colors.onSurface
        AmountTone.MONEY_OUT -> if (amount > 0.0) FinanceTheme.colors.moneyOut else FinanceTheme.colors.onSurface
        AmountTone.MONEY_IN -> if (amount > 0.0) FinanceTheme.colors.moneyIn else FinanceTheme.colors.onSurface
        AmountTone.MONEY_OWED -> if (amount > 0.0) FinanceTheme.colors.moneyOwed else FinanceTheme.colors.onSurface
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
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun AmountTextPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        AmountText(amount = 6549.0, tone = AmountTone.DEFAULT)
    }
}

@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AmountTextPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        AmountText(amount = 2050.50, tone = AmountTone.MONEY_IN)
    }
}

@Preview(name = "Font Scale 1.3x", showBackground = true, fontScale = 1.3f)
@Composable
private fun AmountTextPreviewScale() {
    FinanceAutomationTheme(darkTheme = false) {
        AmountText(amount = 125000.0, tone = AmountTone.MONEY_OWED)
    }
}
