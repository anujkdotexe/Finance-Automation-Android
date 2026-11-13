package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.core.utils.formatRupeesAuto
import com.financeautomation.core.utils.formatRupeesCompact
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

    val exactFormatted = if (includeDecimals == null) {
        formatRupeesAuto(amount)
    } else {
        formatRupees(amount, includeDecimals)
    }
    val exactText = if (prefix.isNotBlank() && !exactFormatted.startsWith("-")) "$prefix$exactFormatted" else exactFormatted
    val compactFormatted = formatRupeesCompact(amount)
    val compactText = if (prefix.isNotBlank() && !compactFormatted.startsWith("-")) "$prefix$compactFormatted" else compactFormatted

    val floorFontSize = 10.sp
    var fontSize by remember(exactText) { mutableStateOf(style.fontSize) }
    var useCompact by remember(exactText) { mutableStateOf(false) }
    var allowWrap by remember(exactText) { mutableStateOf(false) }

    val currentText = if (useCompact) compactText else exactText

    Text(
        text = currentText,
        style = style.copy(fontSize = fontSize),
        color = color,
        maxLines = if (allowWrap) 2 else 1,
        softWrap = allowWrap,
        overflow = TextOverflow.Clip,
        modifier = modifier,
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowWidth) {
                if (fontSize.value > floorFontSize.value) {
                    fontSize = (fontSize.value - 1f).sp
                } else if (!useCompact && exactText != compactText) {
                    useCompact = true
                    fontSize = style.fontSize
                } else if (!allowWrap) {
                    allowWrap = true
                }
            }
        }
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
