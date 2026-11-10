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

// High-End Fintech Light Color Palette (Clean, Modern, Accessible)
val LightPrimary: Color = Color(0xFF2563EB) // Vibrant Royal Blue
val LightOnPrimary: Color = Color(0xFFFFFFFF)
val LightPrimaryContainer: Color = Color(0xFFEFF6FF) // Soft Blue 50
val LightOnPrimaryContainer: Color = Color(0xFF1D4ED8)

val LightSecondary: Color = Color(0xFF0F172A) // Deep Slate
val LightOnSecondary: Color = Color(0xFFFFFFFF)
val LightSecondaryContainer: Color = Color(0xFFF1F5F9) // Slate 100
val LightOnSecondaryContainer: Color = Color(0xFF334155)

val LightTertiary: Color = Color(0xFF059669) // Emerald for Savings & Settled
val LightOnTertiary: Color = Color(0xFFFFFFFF)
val LightTertiaryContainer: Color = Color(0xFFECFDF5)
val LightOnTertiaryContainer: Color = Color(0xFF047857)

val LightError: Color = Color(0xFFDC2626) // Crimson Red
val LightOnError: Color = Color(0xFFFFFFFF)
val LightErrorContainer: Color = Color(0xFFFEF2F2)
val LightOnErrorContainer: Color = Color(0xFFB91C1C)

val LightBackground: Color = Color(0xFFF8FAFC) // Ultra-soft Slate 50
val LightOnBackground: Color = Color(0xFF0F172A)
val LightSurface: Color = Color(0xFFFFFFFF) // Pure Crisp White Cards
val LightOnSurface: Color = Color(0xFF0F172A)
val LightSurfaceVariant: Color = Color(0xFFF1F5F9) // Subtle Container Fill
val LightOnSurfaceVariant: Color = Color(0xFF64748B) // Refined Slate Caption
val LightOutline: Color = Color(0xFFE2E8F0) // Subtle 1dp Stroke
val LightOutlineVariant: Color = Color(0xFFCBD5E1)

// Dark Theme Color Palette (Fallback)
val DarkPrimary: Color = Color(0xFF93C5FD)
val DarkOnPrimary: Color = Color(0xFF1E3A8A)
val DarkPrimaryContainer: Color = Color(0xFF1D4ED8)
val DarkOnPrimaryContainer: Color = Color(0xFFDBEAFE)

val DarkSecondary: Color = Color(0xFFCBD5E1)
val DarkOnSecondary: Color = Color(0xFF1E293B)
val DarkSecondaryContainer: Color = Color(0xFF334155)
val DarkOnSecondaryContainer: Color = Color(0xFFF1F5F9)

val DarkTertiary: Color = Color(0xFF6EE7B7)
val DarkOnTertiary: Color = Color(0xFF064E3B)
val DarkTertiaryContainer: Color = Color(0xFF047857)
val DarkOnTertiaryContainer: Color = Color(0xFFA7F3D0)

val DarkError: Color = Color(0xFFFCA5A5)
val DarkOnError: Color = Color(0xFF7F1D1D)
val DarkErrorContainer: Color = Color(0xFF991B1B)
val DarkOnErrorContainer: Color = Color(0xFFFEE2E2)

val DarkBackground: Color = Color(0xFF0F172A)
val DarkOnBackground: Color = Color(0xFFF8FAFC)
val DarkSurface: Color = Color(0xFF1E293B)
val DarkOnSurface: Color = Color(0xFFF8FAFC)
val DarkSurfaceVariant: Color = Color(0xFF334155)
val DarkOnSurfaceVariant: Color = Color(0xFF94A3B8)
val DarkOutline: Color = Color(0xFF475569)

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
    outline = DarkOutline
)

const val OVERLAY_SCRIM_ARGB: Long = 0x73000000L
val DefaultOverlayScrim: Color = Color(OVERLAY_SCRIM_ARGB)

/**
 * Cohesive visual tokens for Finance Automation (Design Guide 3.1).
 * Carries financial and structural meaning across both light and dark themes.
 */
data class FinanceColors(
    val moneyOut: Color,
    val moneyIn: Color,
    val moneyOwed: Color,
    val brand: Color,
    val info: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val overlayScrim: Color = DefaultOverlayScrim,
    val primary: Color = brand,
    val primaryContainer: Color = surfaceVariant,
    val error: Color = moneyOut
)

val LightFinanceColors = FinanceColors(
    moneyOut = Color(0xFFC62828),
    moneyIn = Color(0xFF2E7D32),
    moneyOwed = Color(0xFFE65100),
    brand = Color(0xFF19227E),
    info = Color(0xFF1465BF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFFE2E8F0),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF64748B),
    overlayScrim = Color(0x73000000)
)

val DarkFinanceColors = FinanceColors(
    moneyOut = Color(0xFFEF9A9A),
    moneyIn = Color(0xFFA5D6A7),
    moneyOwed = Color(0xFFFFCC80),
    brand = Color(0xFFC5CAE9),
    info = Color(0xFF90CAF8),
    surface = Color(0xFF121418),
    surfaceVariant = Color(0xFF1C1F26),
    outline = Color(0xFF2A2E37),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFF94A3B8),
    overlayScrim = Color(0x99000000)
)

val LocalFinanceColors = staticCompositionLocalOf { LightFinanceColors }
