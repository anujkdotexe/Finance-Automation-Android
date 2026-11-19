package com.financeautomation.presentation.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.financeautomation.presentation.screens.drawer.OemBackgroundCard
import com.financeautomation.presentation.theme.FinanceAutomationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OemBackgroundCardUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun oemCardRendersAt320dpWidthWithFontScale2InLightTheme() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.0f)
            ) {
                FinanceAutomationTheme(darkTheme = false) {
                    Box(modifier = Modifier.width(320.dp)) {
                        OemBackgroundCard(
                            isOemDone = false,
                            onToggleOemDone = {},
                            onOpenSettings = {},
                            oemInstruction = "Battery > Background activity",
                            oemFeedback = null,
                            onOemFeedback = {}
                        )
                    }
                }
            }
        }

        // Assert Open Settings button is displayed
        val button = composeTestRule.onNodeWithText("Open OEM settings")
        button.assertIsDisplayed()

        // Assert both chips (Yes and No) are displayed
        val yesChip = composeTestRule.onNodeWithText("Yes")
        val noChip = composeTestRule.onNodeWithText("No")
        yesChip.assertIsDisplayed()
        noChip.assertIsDisplayed()

        // Assert button label height is below two line heights
        val buttonBounds = button.getBoundsInRoot()
        val buttonHeight = buttonBounds.bottom - buttonBounds.top
        assertTrue(buttonHeight <= 80.dp)

        // Assert both chips fit within 320dp width card bounds
        val yesBounds = yesChip.getBoundsInRoot()
        val noBounds = noChip.getBoundsInRoot()
        assertTrue(yesBounds.right <= 320.dp)
        assertTrue(noBounds.right <= 320.dp)
    }

    @Test
    fun oemCardRendersAt320dpWidthWithFontScale2InDarkTheme() {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 1f, fontScale = 2.0f)
            ) {
                FinanceAutomationTheme(darkTheme = true) {
                    Box(modifier = Modifier.width(320.dp)) {
                        OemBackgroundCard(
                            isOemDone = false,
                            onToggleOemDone = {},
                            onOpenSettings = {},
                            oemInstruction = "Battery > Background activity",
                            oemFeedback = null,
                            onOemFeedback = {}
                        )
                    }
                }
            }
        }

        val button = composeTestRule.onNodeWithText("Open OEM settings")
        button.assertIsDisplayed()

        val yesChip = composeTestRule.onNodeWithText("Yes")
        val noChip = composeTestRule.onNodeWithText("No")
        yesChip.assertIsDisplayed()
        noChip.assertIsDisplayed()

        val buttonBounds = button.getBoundsInRoot()
        val buttonHeight = buttonBounds.bottom - buttonBounds.top
        assertTrue(buttonHeight <= 80.dp)

        val yesBounds = yesChip.getBoundsInRoot()
        val noBounds = noChip.getBoundsInRoot()
        assertTrue(yesBounds.right <= 320.dp)
        assertTrue(noBounds.right <= 320.dp)
    }
}
