package com.financeautomation.presentation.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.financeautomation.presentation.viewmodel.ExpenseViewModel
import com.financeautomation.presentation.viewmodel.ReviewInboxViewModel

@Composable
fun ReviewInboxScreen(
    reviewInboxViewModel: ReviewInboxViewModel,
    expenseViewModel: ExpenseViewModel? = null,
    modifier: Modifier = Modifier
) {
    com.financeautomation.presentation.screens.ReviewInboxScreen(
        reviewInboxViewModel = reviewInboxViewModel,
        expenseViewModel = expenseViewModel,
        modifier = modifier
    )
}
