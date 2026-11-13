package com.financeautomation.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing, shape, and size design tokens for Calm Financial Automation.
 * Conforms to Material 3 and Samsung One UI guidelines.
 */
object Spacing {
    val none: Dp = 0.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 6.dp
    val s: Dp = 8.dp
    val compact: Dp = 10.dp
    val m: Dp = 12.dp
    val cardGap: Dp = 14.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val screenEdge: Dp = 16.dp
    val bottomFabClearance: Dp = 88.dp
}

object FinanceShapes {
    val small = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val dialog = RoundedCornerShape(20.dp)
    val sheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val pill = CircleShape
    val chip = CircleShape
}

object FinanceSizes {
    val strokeThin: Dp = 1.dp
    val strokeThick: Dp = 2.dp
    val grabberWidth: Dp = 32.dp
    val grabberHeight: Dp = 4.dp
    val badgeOffset: Dp = 6.dp
    val icon11: Dp = 11.dp
    val icon12: Dp = 12.dp
    val icon13: Dp = 13.dp
    val iconXs: Dp = 14.dp
    val icon16: Dp = 16.dp
    val iconSmall: Dp = 16.dp
    val icon18: Dp = 18.dp
    val iconMedium: Dp = 20.dp
    val icon22: Dp = 22.dp
    val iconLarge: Dp = 24.dp
    val icon28: Dp = 28.dp
    val icon32: Dp = 32.dp
    val buttonHeightSmall: Dp = 34.dp
    val buttonHeight36: Dp = 36.dp
    val avatarSmall: Dp = 36.dp
    val avatar38: Dp = 38.dp
    val buttonHeightMedium: Dp = 40.dp
    val avatarMedium: Dp = 42.dp
    val minTouchTarget: Dp = 48.dp
    val buttonHeightLarge: Dp = 48.dp
    val fabSize: Dp = 56.dp
    val bottomBarHeight: Dp = 64.dp
    val avatarLarge: Dp = 72.dp
    val emptyStateIllustration: Dp = 96.dp
    val chartHeight: Dp = 120.dp
    val diagnosticsHeight: Dp = 240.dp
    val previewMaxHeight: Dp = 280.dp
    val shadowElevationSmall: Dp = 4.dp
    val elevationSheet: Dp = 6.dp
    val elevationDialog: Dp = 24.dp
    val proportionBarHeight: Dp = 8.dp
    val maxSheetHeight: Dp = 540.dp
    val maxOverlayHeight: Dp = 620.dp
}

object FinanceAlphas {
    const val scrim: Float = 0.32f
    const val translucent: Float = 0.5f
    const val disabled: Float = 0.38f
}

object Motion {
    const val DURATION_FAST = 150
    const val DURATION_STANDARD = 250
}

val LocalSpacing = staticCompositionLocalOf { Spacing }
val LocalShapes = staticCompositionLocalOf { FinanceShapes }
val LocalSizes = staticCompositionLocalOf { FinanceSizes }
