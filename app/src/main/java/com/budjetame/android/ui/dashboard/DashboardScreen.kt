package com.budjetame.android.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.CategorySliceDto
import com.budjetame.android.data.api.MonthBucketDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.util.Dates
import com.budjetame.android.util.Money
import java.math.BigDecimal
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.round

/** The neutral gray for the "Uncategorized" slice — the backend sends no
 * color for it, and the rendering choice stays in the frontend (the web
 * app's slate-400). */
private val UncategorizedColor = Color(0xFF94A3B8)

/** slate-200, the web app's neutral fill/stroke for the charts. */
private val Slate200 = Color(0xFFE2E8F0)

// The trend bar chart's geometry and colors, mirrored from the web app's
// TrendChart SVG (column widths, gaps, padding, and the indigo palette).
private val BarWidth = 22.dp
private val BarGap = 12.dp
private val ChartLeftPad = 30.dp
private val ChartTopPad = 20.dp
private val BarLabelHeight = 16.dp
private val ChartHeight = 150.dp
private val BarColor = Color(0xFF4F46E5) // indigo-600
private val BarSelectedColor = Color(0xFF4338CA) // indigo-700

/** The Y axis gridlines at 0/¼/½/¾/1 of the tallest bar. */
private val GridlineFractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

/**
 * The Dashboard (tickets #17, #18): Net Worth front and center, the
 * category pie card with its own reference-month picker and
 * Expenses/Incomes toggle (web parity — the donut card owns its month
 * selector), the Budget card — the current Europe/Rome month's frame from
 * GET /dashboard/budget, rendered raw — and the monthly trend chart with
 * its own Expenses/Incomes toggle over a user-picked From/To month range
 * (GET /dashboard/expense-trend and /dashboard/income-trend). Every number
 * comes from the API — the client only renders; the charts' geometry and
 * the month labels are the frontend's job (spec decision #14).
 */
@Composable
fun DashboardScreen(dashboard: DashboardGateway) {
    val viewModel: DashboardViewModel = viewModel { DashboardViewModel(dashboard) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        DashboardHeader()

        val loadError = state.loadError
        when {
            state.summary == null && loadError != null -> LoadErrorBody(
                message = loadError,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f),
            )
            state.summary == null -> MessageBody(
                text = "Loading…",
                modifier = Modifier.weight(1f),
            )
            else -> DashboardContent(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DashboardHeader() {
    Text(
        text = "Dashboard",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun DashboardContent(
    state: DashboardViewModel.UiState,
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier,
) {
    val summary = state.summary ?: return
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Net Worth never waits on a month change: balances are current.
        item(key = "net-worth") { NetWorthCard(netWorth = summary.net_worth) }
        // The Budget is current-month-only: it ignores the pie card's month
        // selector below, exactly like the web app's card (web issue #66).
        item(key = "budget") { BudgetCard(state = state) }
        item(key = "pie") { PieCard(state = state, viewModel = viewModel) }
        item(key = "trend") { TrendCard(state = state, viewModel = viewModel) }
    }
}

/** The shared card chrome, mirroring the web app's white rounded cards. */
@Composable
private fun DashboardCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun NetWorthCard(netWorth: String) {
    DashboardCard {
        Text(
            text = "NET WORTH",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatEuros(netWorth),
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "The sum of every wallet balance — contact wallets included.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The Budget card (web issue #65): the current Europe/Rome month's frame —
 * Spendable Today big, the "X per day · Y this month" explanation line
 * (Daily Allowance · Monthly Spendable), and a "You're €X over" note when
 * the bucket is negative — the big number then shows 0: future accruals
 * repay the debt (ADR-0012 semantics). Everything is rendered from
 * GET /dashboard/budget raw; the client never computes the frame, and a
 * failed load never looks like an empty Budget (its own error state). The
 * web app hides the card when the account has no Recurring definitions
 * (issue #66); that lands with the Recurring screen (tickets #22–#24).
 */
@Composable
private fun BudgetCard(state: DashboardViewModel.UiState) {
    DashboardCard {
        val budgetError = state.budgetError
        val budget = state.budget
        when {
            budgetError != null -> Text(
                text = budgetError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            budget == null -> Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = "SPENDABLE TODAY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val negative = budget.spendable_today.startsWith("-")
                Text(
                    text = Money.formatEuros(if (negative) "0.00" else budget.spendable_today),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (negative) {
                    Text(
                        text = "You're ${Money.formatEuros(budget.spendable_today.drop(1))} over",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = "${Money.formatEuros(budget.daily_allowance)} per day · " +
                        "${Money.formatEuros(budget.monthly_spendable)} this month",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** The Expenses/Incomes side toggle shared by the pie and the trend cards:
 * a segmented control with the active side highlighted — the web app's
 * KindToggle. */
@Composable
private fun ExpenseIncomeToggle(
    expenseSelected: Boolean,
    onSelect: (expenseSelected: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SegmentedButton(
            selected = expenseSelected,
            onClick = { onSelect(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text("Expenses") },
        )
        SegmentedButton(
            selected = !expenseSelected,
            onClick = { onSelect(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text("Incomes") },
        )
    }
}

/**
 * The category pie card, self-contained (web parity, US27): it owns its
 * reference month — the title ("June 2026 · Expenses by Category"), the
 * Expenses/Incomes toggle, and a "Month" field opening the same
 * MonthPickerDialog the trend card's From/To fields use — with the donut
 * and legend below. Picking a month refetches the summary for it, driving
 * title, donut, and legend together; both pies arrive in one summary
 * response, so the toggle never refetches. The toggle and the month field
 * stay usable while the picked month's summary is in flight — only the
 * data area shows "Loading…" — and the card never titles itself with a
 * month whose data has not arrived: title, donut, and legend render only
 * once the held summary is the requested month's (US27, as before).
 */
@Composable
private fun PieCard(
    state: DashboardViewModel.UiState,
    viewModel: DashboardViewModel,
) {
    DashboardCard {
        // The title carries the month, so it only renders once that month's
        // summary has arrived — never the requested month over stale data.
        if (state.monthInSync) {
            val sideLabel = if (state.pieSide == PieSide.EXPENSE) "Expenses" else "Incomes"
            Text(
                text = "${Dates.monthLabel(state.requestedMonth.toString())} · $sideLabel by Category".uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ExpenseIncomeToggle(
            expenseSelected = state.pieSide == PieSide.EXPENSE,
            onSelect = { expense ->
                viewModel.onPieSideChange(if (expense) PieSide.EXPENSE else PieSide.INCOME)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        var monthPickerOpen by remember { mutableStateOf(false) }
        MonthField(
            label = "Month",
            month = state.requestedMonth,
            onClick = { monthPickerOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        // The data area. While the picked month's summary is in flight, the
        // held slices are still the previous month's — show "Loading…"
        // instead of old data under the new month (US27); the toggle and
        // the month field above stay usable throughout.
        when {
            !state.monthInSync -> Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )

            state.pieSlices.isEmpty() -> {
                val lower = if (state.pieSide == PieSide.EXPENSE) "expenses" else "incomes"
                Text(
                    text = "No $lower recorded in ${Dates.monthLabel(state.requestedMonth.toString())}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            else -> {
                DonutChart(
                    slices = state.pieSlices,
                    centerLabel = Money.formatEuros(state.pieTotal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
                PieLegend(
                    slices = state.pieSlices,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
        if (monthPickerOpen) {
            MonthPickerDialog(
                initial = state.requestedMonth,
                onDismiss = { monthPickerOpen = false },
                onSelect = { month ->
                    viewModel.onPieMonthChange(month)
                    monthPickerOpen = false
                },
            )
        }
    }
}

/** A dependency-free donut (Compose Canvas): one stroke segment per slice,
 * laid out clockwise from 12 o'clock, like the web app's SVG. */
@Composable
private fun DonutChart(
    slices: List<CategorySliceDto>,
    centerLabel: String,
    modifier: Modifier = Modifier,
) {
    val backgroundRing = Slate200
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(176.dp)) {
            val strokeWidth = size.minDimension * 0.18f
            val radius = (size.minDimension - strokeWidth) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val circumference = 2f * PI.toFloat() * radius
            val total = slices.sumOf { BigDecimal(it.amount) }.toFloat()
            drawCircle(
                color = backgroundRing,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth),
            )
            if (total > 0f) {
                var cumulative = 0f
                slices.forEach { slice ->
                    val length = BigDecimal(slice.amount).toFloat() / total * circumference
                    val startAngle = -90f + cumulative / circumference * 360f
                    val sweepAngle = length / circumference * 360f
                    drawArc(
                        color = sliceColor(slice),
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                    cumulative += length
                }
            }
        }
        Text(
            text = centerLabel,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The legend: color dot, icon + name, and "€amount · share%" — the share is
 * the slice's part of the slices' own sum, exactly like the web app. */
@Composable
private fun PieLegend(slices: List<CategorySliceDto>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { BigDecimal(it.amount) }.toDouble()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slices.forEach { slice ->
            val share =
                if (total > 0) round(BigDecimal(slice.amount).toDouble() / total * 100).toInt() else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(color = sliceColor(slice), shape = CircleShape),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = listOfNotNull(slice.icon, slice.name).joinToString(" "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${Money.formatEuros(slice.amount)} · $share%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** A slice's color; a malformed stored color falls back to the neutral gray. */
private fun sliceColor(slice: CategorySliceDto): Color {
    val hex = slice.color ?: return UncategorizedColor
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: IllegalArgumentException) {
        UncategorizedColor
    }
}

/**
 * The monthly trend card (T12, US28): the side toggle, the From/To month
 * range pickers (the web app's two month inputs), and the bar chart —
 * X months, Y totals, bucketed server-side in Europe/Rome. While a new
 * side/range's data is in flight, the loaded trend is still the old one's:
 * the title reads from the requested side and range but the chart only
 * renders when the loaded trend matches them.
 */
@Composable
private fun TrendCard(
    state: DashboardViewModel.UiState,
    viewModel: DashboardViewModel,
) {
    DashboardCard {
        val side = if (state.trendKind == TrendKind.EXPENSE) "Expenses" else "Incomes"
        Text(
            text = "$side Trend · ${Dates.monthLabel(state.trendFrom.toString())} – " +
                Dates.monthLabel(state.trendTo.toString()).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpenseIncomeToggle(
            expenseSelected = state.trendKind == TrendKind.EXPENSE,
            onSelect = { expense ->
                viewModel.onTrendKindChange(if (expense) TrendKind.EXPENSE else TrendKind.INCOME)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
        TrendRangePickers(
            state = state,
            onFromChange = viewModel::onTrendFromChange,
            onToChange = viewModel::onTrendToChange,
            modifier = Modifier.padding(top = 12.dp),
        )
        // A failed load must never look like an empty trend: the error is
        // its own state, cleared before every refetch and re-set on failure.
        val trendError = state.trendError
        if (trendError != null) {
            Text(
                text = trendError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        when {
            !state.trendInSync -> Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )

            else -> {
                val months = state.trend?.data?.months.orEmpty()
                val lower = if (state.trendKind == TrendKind.EXPENSE) "expenses" else "incomes"
                if (months.all { BigDecimal(it.amount).compareTo(BigDecimal.ZERO) == 0 }) {
                    Text(
                        text = "No $lower recorded between " +
                            "${Dates.monthLabel(state.trendFrom.toString())} and " +
                            "${Dates.monthLabel(state.trendTo.toString())}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else {
                    TrendChart(
                        months = months,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

/** Which range end a picker dialog edits. */
private enum class PickerTarget { FROM, TO }

/** The From/To range pickers, mirroring the web app's two month inputs.
 * The swap rule (From after To, or To before From) lives in the ViewModel. */
@Composable
private fun TrendRangePickers(
    state: DashboardViewModel.UiState,
    onFromChange: (YearMonth) -> Unit,
    onToChange: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MonthField(
            label = "From",
            month = state.trendFrom,
            onClick = { pickerTarget = PickerTarget.FROM },
            modifier = Modifier.weight(1f),
        )
        MonthField(
            label = "To",
            month = state.trendTo,
            onClick = { pickerTarget = PickerTarget.TO },
            modifier = Modifier.weight(1f),
        )
    }
    pickerTarget?.let { target ->
        MonthPickerDialog(
            initial = if (target == PickerTarget.FROM) state.trendFrom else state.trendTo,
            onDismiss = { pickerTarget = null },
            onSelect = { month ->
                if (target == PickerTarget.FROM) onFromChange(month) else onToChange(month)
                pickerTarget = null
            },
        )
    }
}

/** One range end: a label and a tappable field showing the compact month. */
@Composable
private fun MonthField(
    label: String,
    month: YearMonth,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Text(
                text = Dates.monthLabelCompact(month.toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * A month-only picker dialog — the web app's `<input type="month">`
 * ported to Material: a year stepper and a 12-month grid, and tapping a
 * month selects it immediately, like the browser widget commits on click.
 */
@Composable
private fun MonthPickerDialog(
    initial: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
) {
    var year by remember { mutableIntStateOf(initial.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("Select month") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { year -= 1 }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Previous year",
                        )
                    }
                    Text(
                        text = year.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { year += 1 }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Next year",
                        )
                    }
                }
                for (rowStart in listOf(1, 4, 7, 10)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (monthValue in rowStart until rowStart + 3) {
                            val selected = year == initial.year && monthValue == initial.monthValue
                            Surface(
                                onClick = { onSelect(YearMonth.of(year, monthValue)) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                                contentColor = if (selected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(2.dp),
                            ) {
                                Text(
                                    text = Month.of(monthValue)
                                        .getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/** The pre-measured text and values a TrendChart draw needs — measured at
 * composition, not per frame. */
private data class ChartLayout(
    val values: List<Float>,
    val maxValue: Float,
    val gridLabelLayouts: List<TextLayoutResult>,
    val monthLabelLayouts: List<TextLayoutResult>,
)

/**
 * A dependency-free bar chart (Compose Canvas): one bar per month, scaled
 * to the tallest, with a Y axis (gridlines + € labels) so the euro
 * magnitude is readable at a glance (T12 AC: X months, Y totals). Wide
 * ranges scroll horizontally so every month stays readable.
 *
 * The plot always fills the card's inner width (ticket #40): the content
 * width is max(fixed geometry, available card width). A short range
 * spreads its bars evenly across the full plot — bar widths unchanged,
 * the gaps grown symmetrically, gridlines spanning the whole plot — while
 * a wide range keeps the fixed geometry and scrolls, exactly as before.
 * Drawing and hit testing share one TrendChartGeometry, so tap targets
 * always move with the bars (tickets #40 and #42 land on the same
 * geometry).
 *
 * The bars carry no always-on labels — an amount above every column was
 * wider than its column on a phone, so neighbouring labels collided. The
 * exact amount is read on demand: tapping a column shows the month and its
 * total in the readout above the chart; tapping it again hides it (the web
 * app's interaction). Zero months render as a light stub, so the month
 * sequence stays visible even when empty.
 */
@Composable
private fun TrendChart(months: List<MonthBucketDto>, modifier: Modifier = Modifier) {
    var selected by remember(months) { mutableStateOf<Int?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelSmall
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val layout = remember(months, textMeasurer, density) {
        val values = months.map { BigDecimal(it.amount).toFloat() }
        val maxValue = max(values.maxOrNull() ?: 0f, 1f)
        ChartLayout(
            values = values,
            maxValue = maxValue,
            gridLabelLayouts = GridlineFractions.map { fraction ->
                val label = if (fraction == 0f) "0" else "€${Math.round(maxValue * fraction)}"
                textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = labelStyle.copy(
                        fontSize = 8.sp,
                        color = axisLabelColor,
                    ),
                    density = density,
                )
            },
            monthLabelLayouts = months.map { bucket ->
                textMeasurer.measure(
                    text = AnnotatedString(Dates.shortMonthLabel(bucket.month)),
                    style = labelStyle.copy(
                        fontSize = 9.sp,
                        color = axisLabelColor,
                    ),
                    density = density,
                )
            },
        )
    }
    Column(modifier = modifier) {
        selected?.let { index ->
            months.getOrNull(index)?.let { bucket ->
                Text(
                    text = "${Dates.monthLabel(bucket.month)} · ${Money.formatEuros(bucket.amount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
        // The measured content width: the card's inner width wins when it
        // is wider than the fixed geometry (short ranges stretch to fill),
        // the fixed geometry wins when the range outgrows the card (wide
        // ranges keep today's layout and scroll horizontally).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fixedWidth = ChartLeftPad + (BarWidth + BarGap) * months.size
            val contentWidth = if (fixedWidth > maxWidth) fixedWidth else maxWidth
            val geometry = remember(months.size, contentWidth, density) {
                TrendChartGeometry(
                    count = months.size,
                    barWidth = with(density) { BarWidth.toPx() },
                    barGap = with(density) { BarGap.toPx() },
                    leftPad = with(density) { ChartLeftPad.toPx() },
                    contentWidth = with(density) { contentWidth.toPx() },
                )
            }
            Canvas(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .height(ChartHeight)
                    .width(contentWidth)
                    .pointerInput(geometry) {
                        detectTapGestures { offset ->
                            // The tap target is the whole column, like the
                            // web app's transparent column rects — the
                            // shared geometry keeps it under its bar.
                            val topPad = ChartTopPad.toPx()
                            val plotHeight = (ChartHeight - ChartTopPad - BarLabelHeight).toPx()
                            val inPlot =
                                offset.y >= topPad && offset.y <= topPad + plotHeight
                            val index = if (inPlot) geometry.columnIndexAt(offset.x) else null
                            selected = when {
                                index == null -> null
                                selected == index -> null
                                else -> index
                            }
                        }
                    },
            ) {
                drawTrendChart(layout, selected, geometry)
            }
        }
    }
}

/** Paints a pre-measured label at a top-left position: the DrawScope text
 * API is gone in this Compose version, so the label goes through
 * TextPainter under a save/translate/restore. */
private fun DrawScope.paintLabel(layout: TextLayoutResult, topLeft: Offset) {
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.translate(topLeft.x, topLeft.y)
        TextPainter.paint(canvas, layout)
        canvas.restore()
    }
}

/** One draw of the bar chart: gridlines with € labels spanning the full
 * plot (the gridlines always run the content's whole width — flush with
 * the last bar in the fixed layout, to the card's inner edge once
 * stretched), the bars (the selected one darkened, zero months as light
 * stubs), and the month labels centered under their bars. Every horizontal
 * position comes from the shared geometry, so the drawn bars and the tap
 * columns can never drift apart. */
private fun DrawScope.drawTrendChart(
    layout: ChartLayout,
    selected: Int?,
    geometry: TrendChartGeometry,
) {
    val topPad = ChartTopPad.toPx()
    val labelHeight = BarLabelHeight.toPx()
    val height = ChartHeight.toPx()
    val plotHeight = height - topPad - labelHeight

    layout.gridLabelLayouts.forEachIndexed { index, label ->
        val fraction = GridlineFractions[index]
        val y = topPad + plotHeight * (1f - fraction)
        drawLine(
            color = Slate200,
            start = Offset(geometry.leftPad, y),
            end = Offset(geometry.contentWidth, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
        paintLabel(
            layout = label,
            topLeft = Offset(
                geometry.leftPad - 4.dp.toPx() - label.size.width,
                y - label.size.height / 2f,
            ),
        )
    }

    layout.values.forEachIndexed { index, value ->
        val barHeight = value / layout.maxValue * plotHeight
        val x = geometry.barLeft(index)
        val y = topPad + plotHeight - barHeight
        drawRoundRect(
            color = when {
                selected == index -> BarSelectedColor
                value > 0f -> BarColor
                else -> Slate200
            },
            topLeft = Offset(x, y),
            size = Size(geometry.barWidth, max(barHeight, if (value > 0f) 2.dp.toPx() else 0f)),
            cornerRadius = CornerRadius(3.dp.toPx()),
        )
        val label = layout.monthLabelLayouts[index]
        paintLabel(
            layout = label,
            topLeft = Offset(
                geometry.barCenter(index) - label.size.width / 2f,
                height - labelHeight + (labelHeight - label.size.height) / 2f,
            ),
        )
    }
}

