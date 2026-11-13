package com.financeautomation.presentation.viewmodel

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * A user-visible text that the UI resolves from string resources, so view models never hold literals.
 * The UI resolves it with stringResource(resId, *args.toTypedArray()) or uiText.asString().
 */
sealed interface UiText {
    val resId: Int
        get() = when (this) {
            is StringResource -> this.resId
            is DynamicString -> 0
        }

    val args: List<Any>
        get() = when (this) {
            is StringResource -> this.args
            is DynamicString -> emptyList()
        }

    data class DynamicString(val value: String) : UiText

    data class StringResource(
        @StringRes override val resId: Int,
        override val args: List<Any> = emptyList()
    ) : UiText {
        constructor(@StringRes resId: Int, vararg args: Any) : this(resId, args.toList())
    }

    @Composable
    fun asString(): String = when (this) {
        is DynamicString -> value
        is StringResource -> if (args.isEmpty()) stringResource(resId) else stringResource(resId, *args.toTypedArray())
    }

    fun asString(context: Context): String = when (this) {
        is DynamicString -> value
        is StringResource -> if (args.isEmpty()) context.getString(resId) else context.getString(resId, *args.toTypedArray())
    }

    companion object {
        operator fun invoke(@StringRes resId: Int, args: List<Any> = emptyList()): UiText =
            StringResource(resId, args)

        fun res(@StringRes resId: Int, vararg args: Any): UiText =
            StringResource(resId, args.toList())

        fun dynamic(value: String): UiText =
            DynamicString(value)
    }
}
