package com.financeautomation.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Master Sheet Specification and Brand Palette Tokens
val SheetNavyBanner: Color = Color(0xFF1E3A8A)
val SheetDebitCrimson: Color = Color(0xFFDC2626)
val SheetCreditGreen: Color = Color(0xFF059669)
val SheetPayableAmber: Color = Color(0xFFD97706)
val SheetSlateHeader: Color = Color(0xFF334155)
val SheetSpacerGray: Color = Color(0xFFF1F5F9)

// Calm Financial Automation - Light Mode Palette Tokens
val LightPrimary: Color = Color(0xFF006874)
val LightOnPrimary: Color = Color(0xFFFFFFFF)
val LightPrimaryContainer: Color = Color(0xFF9EEFFD)
val LightOnPrimaryContainer: Color = Color(0xFF001F24)

val LightSecondary: Color = Color(0xFF4A6267)
val LightOnSecondary: Color = Color(0xFFFFFFFF)
val LightSecondaryContainer: Color = Color(0xFFCDE7ED)
val LightOnSecondaryContainer: Color = Color(0xFF051F23)

val LightTertiary: Color = Color(0xFF3A4664)
val LightOnTertiary: Color = Color(0xFFFFFFFF)
val LightTertiaryContainer: Color = Color(0xFF525E7D)
val LightOnTertiaryContainer: Color = Color(0xFFCCD8FD)

val LightError: Color = Color(0xFFBA1A1A)
val LightOnError: Color = Color(0xFFFFFFFF)
val LightErrorContainer: Color = Color(0xFFFFDAD6)
val LightOnErrorContainer: Color = Color(0xFF93000A)

val LightBackground: Color = Color(0xFFF8FAFA)
val LightOnBackground: Color = Color(0xFF191C1D)
val LightSurface: Color = Color(0xFFF8FAFA)
val LightOnSurface: Color = Color(0xFF191C1D)
val LightSurfaceVariant: Color = Color(0xFFDBE4E6)
val LightOnSurfaceVariant: Color = Color(0xFF3F484A)
val LightOutline: Color = Color(0xFF6F797A)
val LightOutlineVariant: Color = Color(0xFFBFC8CA)

// Extended Surface Container Tones (Light)
val LightSurfaceDim: Color = Color(0xFFD8DADB)
val LightSurfaceBright: Color = Color(0xFFF8FAFA)
val LightSurfaceContainerLowest: Color = Color(0xFFFFFFFF)
val LightSurfaceContainerLow: Color = Color(0xFFECF5F7)
val LightSurfaceContainer: Color = Color(0xFFEDF0F1)
val LightSurfaceContainerHigh: Color = Color(0xFFE7EBEC)
val LightSurfaceContainerHighest: Color = Color(0xFFDBE4E6)

// Calm Financial Automation - Dark Mode Palette Tokens
val DarkPrimary: Color = Color(0xFF4DD8E6)
val DarkOnPrimary: Color = Color(0xFF00363D)
val DarkPrimaryContainer: Color = Color(0xFF004F58)
val DarkOnPrimaryContainer: Color = Color(0xFF97E4F2)

val DarkSecondary: Color = Color(0xFFB1CBD1)
val DarkOnSecondary: Color = Color(0xFF1C3438)
val DarkSecondaryContainer: Color = Color(0xFF334B4F)
val DarkOnSecondaryContainer: Color = Color(0xFFCDE7ED)

val DarkTertiary: Color = Color(0xFFB9C6EA)
val DarkOnTertiary: Color = Color(0xFF23304D)
val DarkTertiaryContainer: Color = Color(0xFF3A4664)
val DarkOnTertiaryContainer: Color = Color(0xFFD9E2FF)

val DarkError: Color = Color(0xFFFFB4AB)
val DarkOnError: Color = Color(0xFF690005)
val DarkErrorContainer: Color = Color(0xFF93000A)
val DarkOnErrorContainer: Color = Color(0xFFFFDAD6)

val DarkBackground: Color = Color(0xFF0F1416)
val DarkOnBackground: Color = Color(0xFFDEE3E5)
val DarkSurface: Color = Color(0xFF0F1416)
val DarkOnSurface: Color = Color(0xFFDEE3E5)
val DarkSurfaceVariant: Color = Color(0xFF262B2D)
val DarkOnSurfaceVariant: Color = Color(0xFFBEC8CA)
val DarkOutline: Color = Color(0xFF899294)
val DarkOutlineVariant: Color = Color(0xFF3F484A)

// Extended Surface Container Tones (Dark)
val DarkSurfaceDim: Color = Color(0xFF0F1416)
val DarkSurfaceBright: Color = Color(0xFF0F1416)
val DarkSurfaceContainerLowest: Color = Color(0xFF0A0F10)
val DarkSurfaceContainerLow: Color = Color(0xFF171C1E)
val DarkSurfaceContainer: Color = Color(0xFF1B2022)
val DarkSurfaceContainerHigh: Color = Color(0xFF262B2D)
val DarkSurfaceContainerHighest: Color = Color(0xFF303638)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant
)

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant
)

const val OVERLAY_SCRIM_ARGB: Long = 0x52000000L
val DefaultOverlayScrim: Color = Color(OVERLAY_SCRIM_ARGB)

/**
 * Cohesive visual tokens for Calm Financial Automation.
 * Carries semantic money colors and full surface container scales across both themes.
 */
data class FinanceColors(
    val moneyOut: Color,
    val moneyIn: Color,
    val moneyOwed: Color,
    val brand: Color,
    val info: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val overlayScrim: Color = DefaultOverlayScrim,
    val primary: Color = brand,
    val primaryContainer: Color = surfaceContainer,
    val error: Color = moneyOut
)

val LightFinanceColors = FinanceColors(
    moneyOut = Color(0xFFBA1A1A),
    moneyIn = Color(0xFF146C2E),
    moneyOwed = Color(0xFF8F4C00),
    brand = LightPrimary,
    info = LightPrimary,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    overlayScrim = Color(0x52000000)
)

val DarkFinanceColors = FinanceColors(
    moneyOut = Color(0xFFFFB4AB),
    moneyIn = Color(0xFF72D6E4),
    moneyOwed = Color(0xFFFFB951),
    brand = DarkPrimary,
    info = DarkPrimary,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    overlayScrim = Color(0x73000000)
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinanceColors }
