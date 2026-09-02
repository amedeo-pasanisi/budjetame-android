package com.budjetame.android.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.CategorySliceDto
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.util.Dates
import com.budjetame.android.util.Money
import java.math.BigDecimal
import kotlin.math.PI
import kotlin.math.round

/** The neutral gray for the "Uncategorized" slice — the backend sends no
 * color for it, and the rendering choice stays in the frontend (the web
 * app's slate-400). */
private val UncategorizedColor = Color(0xFF94A3B8)

/**
 * The Dashboard (ticket #17): Net Worth front and center, the reference
 * month's Income/Expense totals with previous/next month navigation, and
 * the category pie toggled between Expenses and Incomes. Every number comes
 * from GET /dashboard/summary — the client only renders. The trend chart
 * and the Budget card land in ticket #18.
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
        item(key = "month") {
            MonthTotalsCard(
                state = state,
                onPrevious = viewModel::previousMonth,
                onNext = viewModel::nextMonth,
            )
        }
        item(key = "pie") {
            PieCard(
                state = state,
                onPieSideChange = viewModel::onPieSideChange,
            )
        }
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
 * The reference month card: the previous/next arrows drive both the totals
 * and the pie (one summary response serves both). While the new month's
 * summary is in flight, the loaded totals are still the previous month's —
 * show "Loading…" instead of titling them with the new month (US27).
 */
@Composable
private fun MonthTotalsCard(
    state: DashboardViewModel.UiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    DashboardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "Previous month",
                )
            }
            Text(
                text = Dates.monthLabel(state.requestedMonth.toString()),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Next month",
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        val summary = state.summary
        if (summary == null || !state.monthInSync) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                MonthTotal(
                    label = "Expenses",
                    amount = Money.formatEuros(summary.expenses),
                    modifier = Modifier.weight(1f),
                )
                MonthTotal(
                    label = "Incomes",
                    amount = Money.formatEuros(summary.income),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MonthTotal(label: String, amount: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = amount,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The category pie card, toggled between Expenses and Incomes (both pies
 * arrive in one summary response, so the toggle never refetches). While the
 * new pie month's summary is in flight, the loaded data is still the
 * previous month's — never title the pie with the new month (US27).
 */
@Composable
private fun PieCard(
    state: DashboardViewModel.UiState,
    onPieSideChange: (PieSide) -> Unit,
) {
    DashboardCard {
        if (!state.monthInSync) {
            Text(
                text = "Loading…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@DashboardCard
        }
        val sideLabel = if (state.pieSide == PieSide.EXPENSE) "Expenses" else "Incomes"
        Text(
            text = "${Dates.monthLabel(state.requestedMonth.toString())} · $sideLabel by Category".uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            PieSide.entries.forEachIndexed { index, side ->
                SegmentedButton(
                    selected = state.pieSide == side,
                    onClick = { onPieSideChange(side) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PieSide.entries.size,
                    ),
                    label = { Text(if (side == PieSide.EXPENSE) "Expenses" else "Incomes") },
                )
            }
        }
        if (state.pieSlices.isEmpty()) {
            val lower = if (state.pieSide == PieSide.EXPENSE) "expenses" else "incomes"
            Text(
                text = "No $lower recorded in ${Dates.monthLabel(state.requestedMonth.toString())}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
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
}

/** A dependency-free donut (Compose Canvas): one stroke segment per slice,
 * laid out clockwise from 12 o'clock, like the web app's SVG. */
@Composable
private fun DonutChart(
    slices: List<CategorySliceDto>,
    centerLabel: String,
    modifier: Modifier = Modifier,
) {
    val backgroundRing = Color(0xFFE2E8F0) // slate-200, the web app's ring
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

