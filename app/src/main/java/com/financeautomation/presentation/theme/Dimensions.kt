package com.financeautomation.presentation.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing, shape, and motion design tokens (Design Guide 3.3).
 */
object Spacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val screenEdge: Dp = 16.dp
}

object FinanceShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val chip = CircleShape
}

object Motion {
    const val DURATION_FAST = 150
    const val DURATION_STANDARD = 250
}

val LocalSpacing = staticCompositionLocalOf { Spacing }
val LocalShapes = staticCompositionLocalOf { FinanceShapes }

