package com.financeautomation.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import com.financeautomation.R
import com.financeautomation.presentation.components.AmountText
import com.financeautomation.presentation.components.AmountTone
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.components.BudgetBar
import com.financeautomation.presentation.components.SectionTitle
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.viewmodel.CategorySpend

/**
 * Modern Month Switcher card with calendar icon and navigation chevrons.
 * Supports horizontal swipe gestures to change month and tap on month name
 * to open the month picker dialog.
 */
@Composable
fun MonthSwitcher(
    monthLabel: String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectMonthOffset: ((Int) -> Unit)? = null
) {
    var accumulatedDrag by remember { mutableStateOf(0f) }
    var showMonthPicker by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FinanceTheme.sizes.minTouchTarget)
            .clip(FinanceShapes.card)
            .background(FinanceTheme.colors.surfaceContainerLowest)
            .padding(horizontal = FinanceTheme.spacing.xs)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    accumulatedDrag += delta
                },
                onDragStopped = {
                    val threshold = 50f
                    if (accumulatedDrag < -threshold) {
                        onNextMonth()
                    } else if (accumulatedDrag > threshold) {
                        onPreviousMonth()
                    }
                    accumulatedDrag = 0f
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPreviousMonth,
                modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.cd_prev_month),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s),
                modifier = Modifier
                    .clip(FinanceShapes.small)
                    .then(
                        if (onSelectMonthOffset != null) {
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.dashboard_picker_select_month),
                                onClick = { showMonthPicker = true }
                            )
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = FinanceTheme.spacing.xs, vertical = FinanceTheme.spacing.xxs)
            ) {
                Icon(
                    imageVector = Icons.Rounded.CalendarToday,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(FinanceTheme.sizes.icon18)
                )
                Text(
                    text = monthLabel,
                    style = FinanceTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.size(FinanceTheme.sizes.buttonHeightMedium)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.cd_next_month),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    if (showMonthPicker && onSelectMonthOffset != null) {
        MonthPickerDialog(
            monthLabel = monthLabel,
            onMonthOffsetSelected = { offset ->
                onSelectMonthOffset(offset)
                showMonthPicker = false
            },
            onDismiss = { showMonthPicker = false }
        )
    }
}

@Composable
fun MonthPickerDialog(
    monthLabel: String,
    onMonthOffsetSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val kolkataTz = remember { TimeZone.getTimeZone("Asia/Kolkata") }
    val now = remember { Calendar.getInstance(kolkataTz) }
    val currentYear = remember { now.get(Calendar.YEAR) }
    val currentMonth = remember { now.get(Calendar.MONTH) }

    val displayedYear = remember(monthLabel) {
        monthLabel.split(" ").lastOrNull()?.toIntOrNull() ?: currentYear
    }
    var selectedYear by rememberSaveable { mutableStateOf(displayedYear) }

    val monthNames = remember {
        listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedYear-- }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.dashboard_picker_previous_year)
                    )
                }
                Text(
                    text = selectedYear.toString(),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                )
                IconButton(onClick = { selectedYear++ }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.dashboard_picker_next_year)
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)) {
                for (row in 0 until 4) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
                    ) {
                        for (col in 0 until 3) {
                            val monthIndex = row * 3 + col
                            val isCurrentCalendarMonth = (selectedYear == currentYear && monthIndex == currentMonth)
                            val isDisplayedMonth = monthLabel.startsWith(
                                DateFormatSymbols.getInstance(Locale.US).months[monthIndex],
                                ignoreCase = true
                            ) && selectedYear == displayedYear

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(FinanceTheme.sizes.buttonHeightMedium)
                                    .clip(FinanceShapes.small)
                                    .background(
                                        when {
                                            isDisplayedMonth -> MaterialTheme.colorScheme.primaryContainer
                                            isCurrentCalendarMonth -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                        }
                                    )
                                    .clickable {
                                        val offset = (selectedYear - currentYear) * 12 + (monthIndex - currentMonth)
                                        onMonthOffsetSelected(offset)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = monthNames[monthIndex],
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isDisplayedMonth || isCurrentCalendarMonth) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = when {
                                        isDisplayedMonth -> MaterialTheme.colorScheme.onPrimaryContainer
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onMonthOffsetSelected(0)
                }
            ) {
                Text(stringResource(R.string.dashboard_picker_this_month))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * "Where it went" Category spending breakdown with proportional horizontal bars.
 */
@Composable
fun CategoryBreakdownSection(
    categories: List<CategorySpend>,
    hasMore: Boolean,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCategoryClick: ((String) -> Unit)? = null,
    onSeeAllClick: (() -> Unit)? = null,
    onInsightsClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (categories.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(
            title = stringResource(R.string.dashboard_where_it_went),
            secondaryActionText = if (onInsightsClick != null) stringResource(R.string.insights_title) else null,
            onSecondaryActionClick = onInsightsClick,
            secondaryActionContentDescription = stringResource(R.string.dashboard_insights_link_cd),
            actionText = stringResource(R.string.action_see_all),
            onActionClick = {
                if (onSeeAllClick != null) {
                    onSeeAllClick()
                } else {
                    onToggleExpand()
                }
            }
        )

        AppCard {
            Column(verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.cardGap)) {
                categories.forEach { item ->
                    val percentageInt = (item.percentage * 100).toInt()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (onCategoryClick != null) {
                                    Modifier.clickable(role = Role.Button) { onCategoryClick(item.category) }
                                } else Modifier
                            ),
                        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.sm)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.category,
                                style = FinanceTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.s)
                            ) {
                                AmountText(
                                    amount = item.amount,
                                    tone = AmountTone.DEFAULT,
                                    style = FinanceTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "$percentageInt%",
                                    style = FinanceTheme.typography.numeric,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // BudgetBar for categories with a budget; show nothing for categories without a budget
                        if (item.budgetProgress != null) {
                            BudgetBar(
                                progress = item.budgetProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
