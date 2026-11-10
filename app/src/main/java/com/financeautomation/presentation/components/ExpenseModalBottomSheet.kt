package com.financeautomation.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.financeautomation.presentation.viewmodel.ExpenseViewModel

/**
 * In-app ModalBottomSheet wrapper for ExpenseSheet (Design Guide 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseModalBottomSheet(
    viewModel: ExpenseViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isSaved, state.isVoided) {
        if (state.isSaved || state.isVoided) {
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent,
        dragHandle = null,
        modifier = modifier
    ) {
        ExpenseSheet(
            state = state,
            onAmountChanged = viewModel::onAmountChanged,
            onMerchantChanged = viewModel::onMerchantChanged,
            onCategorySelected = viewModel::onCategorySelected,
            onSplitModeChanged = viewModel::onSplitModeChanged,
            onCompanionToggled = viewModel::onCompanionToggled,
            onAddCompanion = viewModel::onAddNewCompanion,
            onCustomShareChanged = viewModel::onCustomShareChanged,
            onConfirmAndSave = viewModel::onConfirmAndSave,
            onVoid = viewModel::onVoid,
            onCancel = onDismiss
        )
    }
}

