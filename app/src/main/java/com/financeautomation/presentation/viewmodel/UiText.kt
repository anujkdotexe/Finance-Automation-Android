package com.financeautomation.presentation.viewmodel

import androidx.annotation.StringRes

/**
 * A user-visible text that the UI resolves from string resources, so view models never hold literals.
 * The UI resolves it with stringResource(resId, *args.toTypedArray()).
 */
data class UiText(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList()
)
