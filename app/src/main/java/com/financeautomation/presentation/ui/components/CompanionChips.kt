package com.financeautomation.presentation.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.domain.model.Companion

@Composable
fun CompanionChips(
    companions: List<Companion>,
    selectedCompanionIds: Set<String>,
    onCompanionToggled: (String) -> Unit,
    onAddCompanion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    com.financeautomation.presentation.components.CompanionChips(
        companions = companions,
        selectedCompanionIds = selectedCompanionIds,
        onCompanionToggled = onCompanionToggled,
        onAddCompanion = onAddCompanion,
        modifier = modifier
    )
}
