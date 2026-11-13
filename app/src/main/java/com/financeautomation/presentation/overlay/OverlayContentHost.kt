package com.financeautomation.presentation.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.financeautomation.R
import com.financeautomation.domain.model.Account
import com.financeautomation.domain.model.Companion
import com.financeautomation.domain.model.TripTag
import com.financeautomation.presentation.components.QuickLogSheet
import com.financeautomation.presentation.components.SheetScaffold
import com.financeautomation.presentation.flows.ChooseActionMenu
import com.financeautomation.presentation.flows.FriendPaidFlowSheet
import com.financeautomation.presentation.flows.IncomeReturnFlowSheet
import com.financeautomation.presentation.flows.OpenDebtItem
import com.financeautomation.presentation.flows.RepaidFriendFlowSheet
import com.financeautomation.presentation.flows.TripModeFlowSheet
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.QuickLogViewModel

/**
 * Screen state machine for overlay flows (Phase 5.6).
 */
enum class OverlayFlowScreen {
    MENU,
    EXPENSE,
    INCOME_RETURN,
    FRIEND_PAID,
    REPAID_FRIEND,
    TRIP_MODE
}

/**
 * Host composable orchestrating navigation between the Choose Action menu and the five logging flows.
 */
@Composable
fun OverlayContentHost(
    initialScreen: OverlayFlowScreen,
    quickLogViewModel: QuickLogViewModel,
    accounts: List<Account>,
    companions: List<Companion>,
    activeTripTag: TripTag?,
    openDebts: List<OpenDebtItem>,
    preselectedFriend: String? = null,
    onPersonChanged: (String) -> Unit = {},
    onConfirmExpense: () -> Unit,
    onConfirmIncome: (amount: Double, account: String, inflowType: String, settler: String, reason: String, explicitRefs: List<String>) -> Unit,
    onConfirmFriendPaid: (amount: Double, creditor: String, whatFor: String) -> Unit,
    onConfirmRepaid: (amount: Double, person: String, timestamp: Long) -> Unit,
    onStartTrip: (tagName: String) -> Unit,
    onEndTrip: () -> Unit,
    onDismiss: () -> Unit,
    onKeyboardFocusChanged: (Boolean) -> Unit,
    isTestMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    var currentScreen by remember { mutableStateOf(initialScreen) }
    val openedFromMenu = remember { initialScreen == OverlayFlowScreen.MENU }
    val quickLogState by quickLogViewModel.uiState.collectAsState()

    val isExpense = currentScreen == OverlayFlowScreen.EXPENSE
    val isEdit = isExpense && !quickLogState.transactionId.isNullOrBlank()
    val isCaptured = isExpense && quickLogState.captureId != null

    val sheetTitle = when (currentScreen) {
        OverlayFlowScreen.MENU -> stringResource(R.string.prompt_choose_action)
        OverlayFlowScreen.EXPENSE -> when {
            isEdit -> "Edit expense"
            isCaptured -> stringResource(R.string.sheet_title_log_payment)
            else -> stringResource(R.string.sheet_title_add_expense)
        }
        OverlayFlowScreen.INCOME_RETURN -> stringResource(R.string.action_menu_income_title)
        OverlayFlowScreen.FRIEND_PAID -> stringResource(R.string.action_menu_friend_paid_title)
        OverlayFlowScreen.REPAID_FRIEND -> stringResource(R.string.action_menu_repaid_friend_title)
        OverlayFlowScreen.TRIP_MODE -> stringResource(R.string.action_menu_trip_mode_title)
    }

    val sheetCaption = if (isCaptured) {
        stringResource(
            R.string.sheet_caption_from_app,
            com.financeautomation.core.constants.PackageWhitelist.appDisplayName(quickLogState.sourcePackage)
        )
    } else null

    val backAction: (() -> Unit)? = if (openedFromMenu && currentScreen != OverlayFlowScreen.MENU) {
        { currentScreen = OverlayFlowScreen.MENU }
    } else null

    SheetScaffold(
        title = sheetTitle,
        caption = sheetCaption,
        onBack = backAction,
        onDismiss = onDismiss,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(horizontal = FinanceTheme.spacing.m, vertical = FinanceTheme.spacing.m)
            .heightIn(max = FinanceTheme.sizes.maxOverlayHeight)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isTestMode) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = FinanceTheme.shapes.sheet,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.health_self_test_banner),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = FinanceTheme.spacing.s)
                    )
                }
            }

            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = ""
            ) { screen ->
            when (screen) {
                OverlayFlowScreen.MENU -> {
                    ChooseActionMenu(
                        onSelectExpense = { currentScreen = OverlayFlowScreen.EXPENSE },
                        onSelectIncome = { currentScreen = OverlayFlowScreen.INCOME_RETURN },
                        onSelectFriendPaid = { currentScreen = OverlayFlowScreen.FRIEND_PAID },
                        onSelectRepaid = { currentScreen = OverlayFlowScreen.REPAID_FRIEND },
                        onSelectTripMode = { currentScreen = OverlayFlowScreen.TRIP_MODE },
                        onDismiss = onDismiss
                    )
                }

                OverlayFlowScreen.EXPENSE -> {
                    val state by quickLogViewModel.uiState.collectAsState()
                    QuickLogSheet(
                        state = state,
                        onAmountChanged = quickLogViewModel::onAmountChanged,
                        onPayeeChanged = quickLogViewModel::onPayeeChanged,
                        onAccountSelected = quickLogViewModel::onAccountSelected,
                        onCategorySelected = quickLogViewModel::onCategorySelected,
                        onSplitModeChanged = quickLogViewModel::onSplitModeChanged,
                        onCompanionToggled = quickLogViewModel::onCompanionToggled,
                        onAddCompanion = quickLogViewModel::onAddNewCompanion,
                        onCustomShareChanged = quickLogViewModel::onCustomShareChanged,
                        onEndTrip = quickLogViewModel::onEndTrip,
                        onConfirmAndSave = onConfirmExpense,
                        onDismiss = {
                            if (openedFromMenu) {
                                currentScreen = OverlayFlowScreen.MENU
                            } else {
                                quickLogViewModel.onDismiss()
                                onDismiss()
                            }
                        },
                        onKeyboardFocusChanged = onKeyboardFocusChanged
                    )
                }

                OverlayFlowScreen.INCOME_RETURN -> {
                    IncomeReturnFlowSheet(
                        accounts = accounts,
                        companions = companions,
                        openDebts = openDebts,
                        preselectedFriend = preselectedFriend,
                        onPersonChanged = onPersonChanged,
                        onBack = {
                            if (openedFromMenu) currentScreen = OverlayFlowScreen.MENU else onDismiss()
                        },
                        onDismiss = onDismiss,
                        onConfirm = onConfirmIncome,
                        onKeyboardFocusChanged = onKeyboardFocusChanged
                    )
                }

                OverlayFlowScreen.FRIEND_PAID -> {
                    FriendPaidFlowSheet(
                        companions = companions,
                        onBack = {
                            if (openedFromMenu) currentScreen = OverlayFlowScreen.MENU else onDismiss()
                        },
                        onDismiss = onDismiss,
                        onConfirm = onConfirmFriendPaid,
                        onKeyboardFocusChanged = onKeyboardFocusChanged
                    )
                }

                OverlayFlowScreen.REPAID_FRIEND -> {
                    RepaidFriendFlowSheet(
                        companions = companions,
                        accounts = accounts,
                        preselectedFriend = preselectedFriend,
                        onBack = {
                            if (openedFromMenu) currentScreen = OverlayFlowScreen.MENU else onDismiss()
                        },
                        onDismiss = onDismiss,
                        onConfirm = onConfirmRepaid,
                        onKeyboardFocusChanged = onKeyboardFocusChanged
                    )
                }

                OverlayFlowScreen.TRIP_MODE -> {
                    TripModeFlowSheet(
                        activeTag = activeTripTag,
                        companions = companions,
                        onBack = {
                            if (openedFromMenu) currentScreen = OverlayFlowScreen.MENU else onDismiss()
                        },
                        onDismiss = onDismiss,
                        onStartTrip = onStartTrip,
                        onEndTrip = onEndTrip,
                        onKeyboardFocusChanged = onKeyboardFocusChanged
                    )
                }
            }
        }
    }
}
}

