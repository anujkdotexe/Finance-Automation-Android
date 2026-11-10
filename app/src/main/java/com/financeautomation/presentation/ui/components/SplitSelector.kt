package com.financeautomation.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.core.model.SplitMode

@Composable
fun SplitSelector(
    selectedMode: SplitMode,
    onModeSelected: (SplitMode) -> Unit,
    modifier: Modifier = Modifier
) {
    com.financeautomation.presentation.components.SplitSelector(
        selectedMode = selectedMode,
        onModeSelected = onModeSelected,
        modifier = modifier
    )
}
