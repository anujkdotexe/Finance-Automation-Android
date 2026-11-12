package com.financeautomation.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Backward compatibility alias for SectionTitle.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    SectionTitle(
        title = title,
        modifier = modifier,
        actionText = actionText,
        onActionClick = onActionClick
    )
}
