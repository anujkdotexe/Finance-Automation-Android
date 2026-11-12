package com.financeautomation.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.financeautomation.domain.model.ThemeMode

/**
 * Standard Material 3 theme wrapper for Finance Automation.
 * Defaults to high-end Fintech Light Theme for pristine clarity and readability.
 */
@Composable
fun FinanceAutomationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    },
    dynamicColor: Boolean = false,
    transparentSystemBars: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                if (transparentSystemBars) {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                } else {
                    window.statusBarColor = colorScheme.background.toArgb()
                    window.navigationBarColor = colorScheme.surface.toArgb()
                    val insetsController = WindowCompat.getInsetsController(window, view)
                    insetsController.isAppearanceLightStatusBars = !darkTheme
                    insetsController.isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    val financeColors = if (darkTheme) DarkFinanceColors else LightFinanceColors

    CompositionLocalProvider(
        LocalFinanceColors provides financeColors,
        LocalFinanceTypography provides FinanceTypographyTokens,
        LocalSpacing provides Spacing,
        LocalShapes provides FinanceShapes,
        LocalSizes provides FinanceSizes
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Direct accessor for token values in the active theme (Design Guide 3).
 */
object FinanceTheme {
    val colors: FinanceColors
        @Composable
        @ReadOnlyComposable
        get() = LocalFinanceColors.current

    val typography: FinanceTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalFinanceTypography.current

    val spacing: Spacing
        @Composable
        @ReadOnlyComposable
        get() = LocalSpacing.current

    val shapes: FinanceShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalShapes.current

    val sizes: FinanceSizes
        @Composable
        @ReadOnlyComposable
        get() = LocalSizes.current
}
