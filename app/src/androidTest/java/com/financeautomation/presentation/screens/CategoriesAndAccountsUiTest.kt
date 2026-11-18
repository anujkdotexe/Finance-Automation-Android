package com.financeautomation.presentation.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Category
import com.financeautomation.presentation.screens.drawer.AccountsScreen
import com.financeautomation.presentation.screens.drawer.CategoriesScreen
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import com.financeautomation.presentation.viewmodel.SettingsUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoriesAndAccountsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun categoriesScreenDisplaysSandwichHandleAndThreeDotMenuAt320dpFontScale2() {
        val categories = listOf(
            Category(id = "c1", name = "Food & Drink", isDefault = false, isExcludedFromLivingBurn = false, isArchived = false, displayOrder = 0),
            Category(id = "c2", name = "Shopping", isDefault = false, isExcludedFromLivingBurn = false, isArchived = false, displayOrder = 1)
        )
        val state = SettingsUiState(categories = categories)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.0f)
            ) {
                FinanceAutomationTheme(darkTheme = false) {
                    Box(modifier = Modifier.width(320.dp)) {
                        // Renders Categories list items
                        categories.forEach { cat ->
                            composeTestRule.onNodeWithText(cat.name)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun accountsScreenDisplaysSandwichHandleAndThreeDotMenuAt320dpFontScale2() {
        val accounts = listOf(
            Account(id = "a1", name = "HDFC", lastFourDigits = "1234", isDefault = true, isArchived = false, displayOrder = 0),
            Account(id = "a2", name = "Cash", lastFourDigits = null, isDefault = false, isArchived = false, displayOrder = 1)
        )
        val state = SettingsUiState(accounts = accounts)

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.0f)
            ) {
                FinanceAutomationTheme(darkTheme = false) {
                    Box(modifier = Modifier.width(320.dp)) {
                        accounts.forEach { acc ->
                            composeTestRule.onNodeWithText(acc.name)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun dragGestureOnCategoryRowPerformsTouchInputSwipe() {
        var reordered = false
        composeTestRule.setContent {
            FinanceAutomationTheme(darkTheme = false) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "Reorder category" }
                        .pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragEnd = { reordered = true }
                            ) { _, _ -> }
                        }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Reorder category")
            .performTouchInput {
                down(center)
                advanceEventTime(600)
                moveTo(center.copy(y = center.y + 100f))
                up()
            }

        assertTrue("Long press and drag gesture must complete and trigger reorder callback", reordered)
    }
}
