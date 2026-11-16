package com.financeautomation.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.financeautomation.core.constants.CategoryConstants

/**
 * Named category color token supporting paired light and dark variants.
 * Guarantees sufficient contrast against both light and dark card surfaces.
 */
data class CategoryColorToken(
    val name: String,
    val light: Color,
    val dark: Color
)

object CategoryColorTokens {
    val Teal: CategoryColorToken = CategoryColorToken(
        name = "Teal",
        light = Color(0xFF006874),
        dark = Color(0xFF2DD4BF)
    )

    val Amber: CategoryColorToken = CategoryColorToken(
        name = "Amber",
        light = Color(0xFFB57400),
        dark = Color(0xFFFBBF24)
    )

    val Sky: CategoryColorToken = CategoryColorToken(
        name = "Sky",
        light = Color(0xFF0284C7),
        dark = Color(0xFF38BDF8)
    )

    val Indigo: CategoryColorToken = CategoryColorToken(
        name = "Indigo",
        light = Color(0xFF3F51B5),
        dark = Color(0xFF818CF8)
    )

    val Purple: CategoryColorToken = CategoryColorToken(
        name = "Purple",
        light = Color(0xFF7C3AED),
        dark = Color(0xFFA78BFA)
    )

    val Rose: CategoryColorToken = CategoryColorToken(
        name = "Rose",
        light = Color(0xFFBE185D),
        dark = Color(0xFFFB7185)
    )

    val Cyan: CategoryColorToken = CategoryColorToken(
        name = "Cyan",
        light = Color(0xFF00838F),
        dark = Color(0xFF22D3EE)
    )

    val Lime: CategoryColorToken = CategoryColorToken(
        name = "Lime",
        light = Color(0xFF4D7C0F),
        dark = Color(0xFFA3E635)
    )

    val Orange: CategoryColorToken = CategoryColorToken(
        name = "Orange",
        light = Color(0xFFC2410C),
        dark = Color(0xFFFB923C)
    )

    val Emerald: CategoryColorToken = CategoryColorToken(
        name = "Emerald",
        light = Color(0xFF047857),
        dark = Color(0xFF34D399)
    )

    val Slate: CategoryColorToken = CategoryColorToken(
        name = "Slate",
        light = Color(0xFF475569),
        dark = Color(0xFF94A3B8)
    )

    /** Complete list of all tokens for mapping, indexing, and stable fallback. */
    val ALL: List<CategoryColorToken> = listOf(
        Amber,
        Teal,
        Sky,
        Indigo,
        Purple,
        Rose,
        Cyan,
        Lime,
        Orange,
        Emerald,
        Slate
    )

    /** Selection palette used in the Add Category sheet. */
    val PALETTE: List<CategoryColorToken> = listOf(
        Teal,
        Amber,
        Indigo,
        Rose,
        Cyan,
        Lime,
        Slate
    )
}

/**
 * Returns the named [CategoryColorToken] mapped to the given category name.
 * Uses a deterministic hash code modulo over [CategoryColorTokens.ALL] as a stable fallback
 * for custom or unknown categories.
 */
fun categoryColorToken(categoryName: String): CategoryColorToken {
    val normalized = categoryName.trim().lowercase()
    return when (normalized) {
        "food & drink", "food and drink", "dining out" -> CategoryColorTokens.Amber
        "groceries" -> CategoryColorTokens.Emerald
        "transit" -> CategoryColorTokens.Sky
        "subscriptions" -> CategoryColorTokens.Purple
        "shopping" -> CategoryColorTokens.Rose
        "trip/travel", "trip travel", "travel" -> CategoryColorTokens.Cyan
        "health" -> CategoryColorTokens.Teal
        "family" -> CategoryColorTokens.Indigo
        "savings" -> CategoryColorTokens.Lime
        "other", "transfer" -> CategoryColorTokens.Slate
        else -> {
            val hash = (normalized.hashCode() and 0x7FFFFFFF)
            CategoryColorTokens.ALL[hash % CategoryColorTokens.ALL.size]
        }
    }
}

/** Resolves dot color for a category name for a given dark mode setting. */
fun categoryDotColor(categoryName: String, isDark: Boolean): Color {
    val token = categoryColorToken(categoryName)
    return if (isDark) token.dark else token.light
}

/** Resolves dot color for a domain category, honoring custom chosen color in iconName if present. */
fun categoryDotColor(category: com.financeautomation.domain.model.Category, isDark: Boolean): Color {
    val fromToken = CategoryColorTokens.ALL.find { it.name.equals(category.iconName, ignoreCase = true) }
    val token = fromToken ?: categoryColorToken(category.name)
    return if (isDark) token.dark else token.light
}

/** Composable resolving dot color for a category name observing active dark theme. */
@Composable
fun categoryDotColor(categoryName: String): Color {
    return categoryDotColor(categoryName, isSystemInDarkTheme())
}

/** Composable resolving dot color for a domain category observing active dark theme. */
@Composable
fun categoryDotColor(category: com.financeautomation.domain.model.Category): Color {
    return categoryDotColor(category, isSystemInDarkTheme())
}

/** Resolves dot color by index for a given dark mode setting. */
fun categoryDotColorByIndex(index: Int, isDark: Boolean): Color {
    val token = CategoryColorTokens.ALL[Math.floorMod(index, CategoryColorTokens.ALL.size)]
    return if (isDark) token.dark else token.light
}

/** Composable resolving dot color by index observing active dark theme. */
@Composable
fun categoryDotColorByIndex(index: Int): Color {
    return categoryDotColorByIndex(index, isSystemInDarkTheme())
}

/** Resolves the category picker palette colors for a given dark mode setting. */
fun categoryPaletteColors(isDark: Boolean): List<Color> {
    return CategoryColorTokens.PALETTE.map { if (isDark) it.dark else it.light }
}

/** Composable resolving the category picker palette colors observing active dark theme. */
@Composable
fun categoryPaletteColors(): List<Color> {
    return categoryPaletteColors(isSystemInDarkTheme())
}
