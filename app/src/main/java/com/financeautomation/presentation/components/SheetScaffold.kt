package com.financeautomation.presentation.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.theme.FinanceTheme

/**
 * The single bottom sheet shell used across the floating overlay and ModalBottomSheetActivity (Design Guide 4).
 * Enforces grabber, title, scrollable content area, sticky action row, and system insets.
 */
@Composable
fun SheetScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    stickyAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = FinanceTheme.shapes.sheet,
        color = FinanceTheme.colors.surface,
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume clicks to prevent dismissal */ }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Drag grabber handle
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .background(FinanceTheme.colors.outline, FinanceTheme.shapes.chip)
            )

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.s))

            // Sheet title header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = FinanceTheme.typography.titleMedium,
                    color = FinanceTheme.colors.onSurface
                )

                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = FinanceTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(FinanceTheme.spacing.m))

            // Body content slot
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }

            // Sticky action row if provided
            if (stickyAction != null) {
                Spacer(modifier = Modifier.height(FinanceTheme.spacing.l))
                Box(modifier = Modifier.fillMaxWidth()) {
                    stickyAction()
                }
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Composable
private fun SheetScaffoldPreviewLight() {
    FinanceAutomationTheme(darkTheme = false) {
        SheetScaffold(
            title = "Enter Amount ₹",
            onDismiss = {},
            stickyAction = {
                PrimaryButton(text = "Save", onClick = {})
            }
        ) {
            Text(text = "Sheet content slot", style = FinanceTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Composable
private fun SheetScaffoldPreviewDark() {
    FinanceAutomationTheme(darkTheme = true) {
        SheetScaffold(
            title = "Choose Action",
            onDismiss = {}
        ) {
            Text(text = "Action options", style = FinanceTheme.typography.bodyMedium)
        }
    }
}

@Preview(name = "Large Font 200%", fontScale = 2.0f, showBackground = true)
@Composable
private fun SheetScaffoldPreviewLargeFont() {
    FinanceAutomationTheme(darkTheme = false) {
        SheetScaffold(
            title = "Select Category",
            onDismiss = {}
        ) {
            Text(text = "Categories", style = FinanceTheme.typography.bodyMedium)
        }
    }
}

