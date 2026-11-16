package com.financeautomation.domain.usecase

import com.financeautomation.core.constants.CategoryConstants
import com.financeautomation.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPickerResolverTest {

    @Test
    fun resolve_withNoCustomCategories_returnsDefaultCategories() {
        val resolved = CategoryPickerResolver.resolve(emptyList())

        assertEquals(CategoryConstants.DEFAULT_CATEGORIES.size, resolved.size)
        assertEquals(CategoryConstants.DEFAULT_CATEGORIES, resolved)
    }

    @Test
    fun resolve_withCustomCategories_appendsUserCategoriesWithoutDuplicates() {
        val custom = listOf(
            Category(id = "cat_books", name = "Books & Learning", displayOrder = 1),
            Category(id = "cat_pets", name = "Pet Care", displayOrder = 2)
        )

        val resolved = CategoryPickerResolver.resolve(custom)

        assertEquals(CategoryConstants.DEFAULT_CATEGORIES.size + 2, resolved.size)
        assertTrue(resolved.contains("Books & Learning"))
        assertTrue(resolved.contains("Pet Care"))
    }

    @Test
    fun resolve_deduplicatesCaseFoldedAndTrimmedDuplicates() {
        val custom = listOf(
            Category(id = "dup_1", name = "  food & drink  "),
            Category(id = "dup_2", name = "groceries"),
            Category(id = "dup_3", name = "SAVINGS"),
            Category(id = "valid_custom", name = "Hobbies")
        )

        val resolved = CategoryPickerResolver.resolve(custom)

        assertEquals(CategoryConstants.DEFAULT_CATEGORIES.size + 1, resolved.size)
        assertTrue(resolved.contains("Hobbies"))
        // Original default casing is preserved
        assertTrue(resolved.contains("Food & Drink"))
        assertTrue(resolved.contains("Groceries"))
        assertTrue(resolved.contains("Savings"))
    }

    @Test
    fun resolve_filtersOutArchivedAndBlankCategories() {
        val custom = listOf(
            Category(id = "archived", name = "Old Gym", isArchived = true),
            Category(id = "blank", name = "   "),
            Category(id = "active", name = "Climbing Gym", isArchived = false)
        )

        val resolved = CategoryPickerResolver.resolve(custom)

        assertFalse(resolved.contains("Old Gym"))
        assertFalse(resolved.contains(""))
        assertTrue(resolved.contains("Climbing Gym"))
    }
}
