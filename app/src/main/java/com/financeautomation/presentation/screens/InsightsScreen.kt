package com.financeautomation.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.financeautomation.R
import com.financeautomation.core.utils.formatRupees
import com.financeautomation.core.utils.formatRupeesCompact
import com.financeautomation.domain.model.Category
import com.financeautomation.domain.usecase.InsightsSlice
import com.financeautomation.domain.usecase.MonthlyBurn
import com.financeautomation.presentation.components.AppCard
import com.financeautomation.presentation.components.EmptyState
import com.financeautomation.presentation.theme.FinanceShapes
import com.financeautomation.presentation.theme.FinanceTheme
import com.financeautomation.presentation.theme.categoryChartColor
import com.financeautomation.presentation.theme.isAppInDarkTheme
import com.financeautomation.presentation.viewmodel.InsightsViewModel
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val MONTH_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM", Locale.US)

/**
 * Insights: a donut of the selected month's spending by category and a bar chart of Living burn for the last six
 * months. Both are drawn with Compose Canvas.
 *
 * [onOpenCategory] is called when a donut slice or legend row is tapped, with the month shown and the category name.
 * The host opens Transactions filtered to that category and month.
 */
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onOpenCategory: (month: YearMonth, category: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val palette = insightsPalette()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = FinanceTheme.spacing.l, vertical = FinanceTheme.spacing.m),
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.cardGap)
    ) {
        MonthSwitcher(
            monthLabel = state.selectedMonth.format(MONTH_TITLE),
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth
        )

        if (state.hasNoData) {
            EmptyState(message = stringResource(R.string.insights_empty))
        } else {
            AppCard {
                Text(
                    text = stringResource(R.string.insights_by_category_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.slices.isEmpty()) {
                    Text(
                        text = stringResource(R.string.insights_no_spend_month),
                        style = FinanceTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = FinanceTheme.spacing.l)
                    )
                } else {
                    DonutChart(
                        slices = state.slices,
                        total = state.total,
                        palette = palette,
                        categories = state.categories,
                        onSliceClick = { slice -> if (!slice.isOther) onOpenCategory(state.selectedMonth, slice.category) },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(vertical = FinanceTheme.spacing.s)
                    )
                    Legend(
                        slices = state.slices,
                        palette = palette,
                        categories = state.categories,
                        onClick = { slice -> if (!slice.isOther) onOpenCategory(state.selectedMonth, slice.category) }
                    )
                }
            }

            AppCard {
                Text(
                    text = stringResource(R.string.insights_last_months_title),
                    style = FinanceTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                BarChart(series = state.series)
            }
        }
    }
}

/** At most six distinct colours: five categories plus the grouped "Other", all from the theme. */
@Composable
private fun insightsPalette(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    FinanceTheme.colors.moneyOwed,
    MaterialTheme.colorScheme.tertiary,
    FinanceTheme.colors.moneyIn,
    MaterialTheme.colorScheme.secondary,
    FinanceTheme.colors.outline
)

internal fun sliceColor(
    palette: List<Color>,
    index: Int,
    slice: InsightsSlice,
    categories: List<Category> = emptyList(),
    isDark: Boolean = false
): Color {
    if (slice.isOther) return palette.last()
    val match = categories.firstOrNull { it.name.trim().equals(slice.category.trim(), ignoreCase = true) }
    return if (match != null) {
        categoryChartColor(match, isDark)
    } else {
        palette[index.coerceAtMost(palette.size - 2)]
    }
}

@Composable
private fun DonutChart(
    slices: List<InsightsSlice>,
    total: Double,
    palette: List<Color>,
    categories: List<Category> = emptyList(),
    onSliceClick: (InsightsSlice) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = stringResource(R.string.insights_donut_cd, formatRupees(total), slices.size)
    val track = FinanceTheme.colors.surfaceContainerHigh
    val isDark = isAppInDarkTheme()
    val colors = slices.mapIndexed { i, s -> sliceColor(palette, i, s, categories, isDark) }
    Box(
        modifier = modifier
            .size(FinanceTheme.sizes.emptyStateIllustration * 2)
            .clearAndSetSemantics { contentDescription = summary },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(slices) {
                    detectTapGestures { tap ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = tap.x - center.x
                        val dy = tap.y - center.y
                        val outer = minOf(size.width, size.height) / 2f
                        val distance = hypot(dx, dy)
                        // Only taps on the ring count; the hole in the middle is ignored
                        if (distance < outer * 0.55f || distance > outer) return@detectTapGestures
                        // Angle measured clockwise from the top, like the drawing
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90.0
                        if (angle < 0) angle += 360.0
                        var start = 0.0
                        for (slice in slices) {
                            val sweep = slice.percent / 100.0 * 360.0
                            if (angle >= start && angle < start + sweep) {
                                onSliceClick(slice)
                                return@detectTapGestures
                            }
                            start += sweep
                        }
                    }
                }
        ) {
            val strokeWidth = size.minDimension * 0.18f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            var startAngle = -90f
            slices.forEachIndexed { i, slice ->
                val sweep = (slice.percent / 100.0 * 360.0).toFloat()
                // A thin gap between slices keeps neighbours apart without a border
                val gap = if (slices.size > 1) 1.5f else 0f
                drawArc(
                    color = colors[i],
                    startAngle = startAngle + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt)
                )
                startAngle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatRupees(total),
                style = FinanceTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.insights_donut_center_label),
                style = FinanceTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Legend(
    slices: List<InsightsSlice>,
    palette: List<Color>,
    categories: List<Category> = emptyList(),
    onClick: (InsightsSlice) -> Unit
) {
    val isDark = isAppInDarkTheme()
    Column(modifier = Modifier.fillMaxWidth()) {
        slices.forEachIndexed { index, slice ->
            val percent = slice.percent.roundToInt()
            val rowDescription = if (slice.isOther) {
                stringResource(R.string.insights_legend_other_cd, formatRupees(slice.amount), percent)
            } else {
                stringResource(R.string.insights_legend_row_cd, slice.category, formatRupees(slice.amount), percent)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = FinanceTheme.sizes.minTouchTarget)
                    .clip(FinanceShapes.small)
                    .then(if (slice.isOther) Modifier else Modifier.clickable(onClickLabel = rowDescription) { onClick(slice) })
                    .padding(vertical = FinanceTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.m)
            ) {
                Box(
                    modifier = Modifier
                        .size(FinanceTheme.spacing.compact)
                        .clip(CircleShape)
                        .background(sliceColor(palette, index, slice, categories, isDark))
                )
                Text(
                    text = if (slice.isOther) stringResource(R.string.insights_other) else slice.category,
                    style = FinanceTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatRupees(slice.amount),
                    style = FinanceTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.insights_percent, percent),
                    style = FinanceTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.defaultMinSize(minWidth = FinanceTheme.sizes.icon32)
                )
            }
        }
    }
}

@Composable
private fun BarChart(series: List<MonthlyBurn>) {
    val max = series.maxOfOrNull { it.amount }?.takeIf { it > 0.0 } ?: 1.0
    val summary = stringResource(
        R.string.insights_bars_cd,
        series.joinToString(", ") { "${it.month.format(MONTH_SHORT)} ${formatRupees(it.amount)}" }
    )
    val currentColor = MaterialTheme.colorScheme.primary
    val pastColor = MaterialTheme.colorScheme.secondary
    val baseline = FinanceTheme.colors.outlineVariant
    val hairline = FinanceTheme.sizes.strokeThin
    val minBar = FinanceTheme.spacing.xxs
    val corner = FinanceTheme.spacing.xs

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = summary },
        verticalArrangement = Arrangement.spacedBy(FinanceTheme.spacing.xs)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(FinanceTheme.sizes.chartHeight)
        ) {
            val count = series.size.coerceAtLeast(1)
            val slot = size.width / count
            val barWidth = slot * 0.5f
            val base = size.height
            drawLine(baseline, Offset(0f, base), Offset(size.width, base), strokeWidth = hairline.toPx())
            series.forEachIndexed { i, item ->
                val barHeight = if (item.amount <= 0.0) 0f else (item.amount / max * (base - minBar.toPx())).toFloat().coerceAtLeast(minBar.toPx())
                drawRoundRect(
                    color = if (i == series.lastIndex) currentColor else pastColor,
                    topLeft = Offset(slot * i + (slot - barWidth) / 2f, base - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner.toPx(), corner.toPx())
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            series.forEach { item ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = item.month.format(MONTH_SHORT),
                        style = FinanceTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Text(
                        text = formatRupeesCompact(item.amount),
                        style = FinanceTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
