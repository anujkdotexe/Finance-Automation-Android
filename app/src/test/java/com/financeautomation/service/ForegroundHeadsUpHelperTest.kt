package com.financeautomation.service

import com.financeautomation.domain.usecase.PredictCategoryUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForegroundHeadsUpHelperTest {

    @Test
    fun offersOnlyLiveCategories() {
        val userCategories = listOf("Books", "Fitness", "Hobbies")
        val (cat1, cat2) = ForegroundHeadsUpHelper.pickCategories(
            payee = "Kindle",
            availableCategories = userCategories
        )

        assertEquals("Books", cat1)
        assertEquals("Fitness", cat2)
    }

    @Test
    fun emptyCategoriesListReturnsNullPair() {
        val (cat1, cat2) = ForegroundHeadsUpHelper.pickCategories(
            payee = "Unknown",
            availableCategories = emptyList()
        )

        assertNull("First category must be null when list is empty", cat1)
        assertNull("Second category must be null when list is empty", cat2)
    }

    @Test
    fun predictCategoryUseCaseRanksLiveCategories() {
        val predictUseCase = PredictCategoryUseCase()
        val userCategories = listOf("Fuel", "Groceries", "Dining")
        val payeeHistory = listOf("Dining", "Dining")

        val (cat1, cat2) = ForegroundHeadsUpHelper.pickCategories(
            payee = "Starbucks",
            availableCategories = userCategories,
            payeeHistory = payeeHistory,
            predictCategoryUseCase = predictUseCase
        )

        assertEquals("Dining", cat1)
        assertEquals("Fuel", cat2)
    }
}
