package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * Standard FilledButton: 48dp high, pill, primary background, onPrimary 14 Medium label, one line, max 20 chars.
 * Used for primary actions: Confirm, Save, Save expense, Save income, Save subscription, Record debt,
 * Record inflow, Record repayment, Start new trip, Add expense, Sync now, Yes, Sign in with Google, etc.
 */
@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val displayLabel = if (text.length > 20) text.take(20) else text
    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = FinanceShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = FinanceTheme.spacing.l),
        modifier = modifier
            .height(FinanceTheme.sizes.buttonHeight)
            .defaultMinSize(minHeight = FinanceTheme.sizes.buttonHeight)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(FinanceTheme.sizes.iconMedium),
                strokeWidth = FinanceTheme.sizes.strokeThick,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(
                text = displayLabel,
                style = FinanceTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Standard OutlinedButton: 48dp high, pill, 1dp primary border, primary label, transparent background.
 * Used for secondary actions: Edit (on cards), Cancel, Not yet, Skip this time, Back up now, Dismiss,
 * Void, End trip, Clear search, Search all months, Go to current month, Go to Dashboard, Retry, Continue manually.
 */
@Composable
fun OutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val displayLabel = if (text.length > 20) text.take(20) else text
    val borderColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val textColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    M3OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = FinanceShapes.pill,
        border = BorderStroke(FinanceTheme.sizes.strokeThin, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = textColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        contentPadding = PaddingValues(horizontal = FinanceTheme.spacing.l),
        modifier = modifier
            .height(FinanceTheme.sizes.buttonHeight)
            .defaultMinSize(minHeight = FinanceTheme.sizes.buttonHeight)
    ) {
        Text(
            text = displayLabel,
            style = FinanceTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Standard HeaderActionButton: 40dp high, pill, 1dp primary border, primary label.
 * Only in the top bar and in the Needs Review banner.
 * Used for: Confirm all, Add, Add category, Add friend, Review.
 */
@Composable
fun HeaderActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val borderColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val textColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = FinanceShapes.pill,
        border = BorderStroke(FinanceTheme.sizes.strokeThin, borderColor),
        color = Color.Transparent,
        contentColor = textColor,
        modifier = modifier
            .height(FinanceTheme.sizes.headerActionHeight)
            .defaultMinSize(minHeight = FinanceTheme.sizes.headerActionHeight)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = FinanceTheme.spacing.m),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = FinanceTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = textColor
            )
        }
    }
}

/**
 * Standard LinkButton: no box, primary label, 14 Medium. Only for inline links.
 * Used for: See all, Details, Privacy policy, Open source licences, Open in Sheets, Change, Undo.
 */
@Composable
fun LinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val textColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Text(
        text = text,
        style = FinanceTheme.typography.titleSmall,
        color = textColor,
        modifier = modifier
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = FinanceTheme.spacing.xs)
    )
}

/**
 * Standard button pair in one row: equal width, equal height (48dp), 12dp gap, Outlined on left, Filled on right.
 */
@Composable
fun ButtonPair(
    outlinedText: String,
    onOutlinedClick: () -> Unit,
    filledText: String,
    onFilledClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlinedEnabled: Boolean = true,
    filledEnabled: Boolean = true,
    isFilledLoading: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.sizes.cardGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            text = outlinedText,
            onClick = onOutlinedClick,
            enabled = outlinedEnabled,
            modifier = Modifier.weight(1f)
        )
        FilledButton(
            text = filledText,
            onClick = onFilledClick,
            enabled = filledEnabled,
            isLoading = isFilledLoading,
            modifier = Modifier.weight(1f)
        )
    }
}

// Backwards-compatibility aliases for existing calls
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    FilledButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        isLoading = isLoading
    )
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled
    )
}

@Composable
fun TonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    FilledButton(
        text = text,
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled
    )
}

@Composable
fun TextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    LinkButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled
    )
}

// Previews in normal and 1.3 font scale
@Preview(name = "Buttons Normal Font - Light", showBackground = true, fontScale = 1.0f)
@Composable
private fun ButtonsPreviewNormalLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            FilledButton(text = "Confirm", onClick = {})
            OutlinedButton(text = "Edit", onClick = {})
            HeaderActionButton(text = "Review", onClick = {})
            LinkButton(text = "See all", onClick = {})
            ButtonPair(
                outlinedText = "Cancel",
                onOutlinedClick = {},
                filledText = "Save",
                onFilledClick = {}
            )
        }
    }
}

@Preview(name = "Buttons Font 1.3 - Light", showBackground = true, fontScale = 1.3f)
@Composable
private fun ButtonsPreviewLargeFontLight() {
    FinanceAutomationTheme(darkTheme = false) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            FilledButton(text = "Confirm", onClick = {})
            OutlinedButton(text = "Edit", onClick = {})
            HeaderActionButton(text = "Review", onClick = {})
            LinkButton(text = "See all", onClick = {})
            ButtonPair(
                outlinedText = "Cancel",
                onOutlinedClick = {},
                filledText = "Save",
                onFilledClick = {}
            )
        }
    }
}

@Preview(name = "Buttons Normal Font - Dark", showBackground = true, fontScale = 1.0f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ButtonsPreviewNormalDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            FilledButton(text = "Confirm", onClick = {})
            OutlinedButton(text = "Edit", onClick = {})
            HeaderActionButton(text = "Review", onClick = {})
            LinkButton(text = "See all", onClick = {})
            ButtonPair(
                outlinedText = "Cancel",
                onOutlinedClick = {},
                filledText = "Save",
                onFilledClick = {}
            )
        }
    }
}

@Preview(name = "Buttons Font 1.3 - Dark", showBackground = true, fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ButtonsPreviewLargeFontDark() {
    FinanceAutomationTheme(darkTheme = true) {
        Column(
            modifier = Modifier.padding(FinanceTheme.spacing.l),
            verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
        ) {
            FilledButton(text = "Confirm", onClick = {})
            OutlinedButton(text = "Edit", onClick = {})
            HeaderActionButton(text = "Review", onClick = {})
            LinkButton(text = "See all", onClick = {})
            ButtonPair(
                outlinedText = "Cancel",
                onOutlinedClick = {},
                filledText = "Save",
                onFilledClick = {}
            )
        }
    }
}
