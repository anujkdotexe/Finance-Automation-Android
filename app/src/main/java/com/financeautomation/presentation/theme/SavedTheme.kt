package com.financeautomation.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import com.financeautomation.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The saved theme choice as read from storage: not yet read, or read. */
sealed interface SavedTheme {
    data object Loading : SavedTheme
    data class Loaded(val mode: ThemeMode) : SavedTheme
}

/**
 * Whether the dark scheme applies, or null while the saved choice has not been read yet. Content must not be drawn
 * while this is null: drawing with the phone theme first and switching later shows the wrong theme for a moment.
 */
fun resolveSavedDark(saved: SavedTheme, systemDark: Boolean): Boolean? = when (saved) {
    SavedTheme.Loading -> null
    is SavedTheme.Loaded -> resolveDarkTheme(saved.mode, systemDark)
}

/**
 * Draws [content] in the saved theme. Draws nothing until the first saved value has been read, so a window never
 * shows the phone theme first.
 */
@Composable
fun SavedThemeHost(
    savedMode: Flow<ThemeMode>,
    transparentSystemBars: Boolean = false,
    content: @Composable () -> Unit
) {
    val saved by remember(savedMode) { savedMode.map<ThemeMode, SavedTheme> { SavedTheme.Loaded(it) } }
        .collectAsState(initial = SavedTheme.Loading)
    val loaded = saved as? SavedTheme.Loaded ?: return
    FinanceAutomationTheme(
        themeMode = loaded.mode,
        transparentSystemBars = transparentSystemBars,
        content = content
    )
}
