package com.budjetame.android.ui.dashboard

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.MonthBucketDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.util.Dates
import java.time.YearMonth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trend chart's press-and-hold value chip (ticket #42) through the real
 * DashboardScreen: a press on a column holds the amount chip above that
 * bar and the release hides it again — amount only, no readout line, zero
 * months included, and a full-height bar's chip is clamped inside the
 * chart's top edge. The chart's geometry constants are mirrored here (they
 * live privately in DashboardScreen.kt); all press positions derive from
 * the canvas' measured size, so the presses land on the bars at any screen
 * width.
 */
@RunWith(AndroidJUnit4::class)
class TrendChartTest {

    @get:Rule
    val composeRule = createComposeRule()

    // The chart geometry mirrored from DashboardScreen.kt, in dp.
    private val chartLeftPad = 30f
    private val chartBarWidth = 22f
    private val chartBarGap = 12f
    private val chartTopPad = 20f
    private val chartPlotHeight = 114f // ChartHeight − ChartTopPad − BarLabelHeight
    private val chartHeight = 150f

    /** One amount per month of the default trend range (now − 5 … now):
     * index 1 and 4 are zero months, index 2 is the tallest bar. */
    private val amounts = listOf("42.50", "0.00", "120.00", "3.00", "0.00", "7.25")

    private lateinit var dashboard: TrendFixtureGateway

    private fun launchDashboard() {
        dashboard = TrendFixtureGateway(amounts)
        composeRule.setContent { DashboardScreen(dashboard = dashboard) }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("NET WORTH").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun chartNode() = composeRule.onNodeWithTag("trend-chart")

    /** The fixture's trend months (the range the ViewModel requested). */
    private fun fixtureMonths(): List<YearMonth> {
        val from = YearMonth.parse(dashboard.fetchedFrom!!)
        val to = YearMonth.parse(dashboard.fetchedTo!!)
        return generateSequence(from) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(to) }
            .toList()
    }

    /** Scrolls the dashboard list until the whole chart is on screen. */
    private fun bringTrendChartIntoView() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("trend-chart").fetchSemanticsNodes().isNotEmpty()
        }
        repeat(12) {
            if (composeRule.onAllNodesWithTag("trend-chart").fetchSemanticsNodes().isEmpty()) {
                return@repeat
            }
            val bounds = chartNode().fetchSemanticsNode().boundsInRoot
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            if (bounds.top >= root.top && bounds.bottom <= root.bottom) return
            composeRule.onRoot().performTouchInput { swipeUp() }
        }
        chartNode().assertIsDisplayed()
    }

    /** The canvas' pixel density, from its fixed 150 dp height. */
    private fun chartDensity(): Float =
        chartNode().fetchSemanticsNode().size.height / chartHeight

    private fun px(dp: Float): Float = dp * chartDensity()

    /** The center of column [index], from the canvas' measured width and
     * the same stretch rule TrendChartGeometry applies. */
    private fun columnCenterX(index: Int): Float {
        val node = chartNode().fetchSemanticsNode()
        val density = node.size.height / chartHeight
        val contentWidth = node.size.width.toFloat()
        val leftPad = chartLeftPad * density
        val barWidth = chartBarWidth * density
        val fixedWidth = leftPad + amounts.size * (barWidth + chartBarGap * density)
        val gap = if (contentWidth <= fixedWidth) chartBarGap * density
        else (contentWidth - leftPad - amounts.size * barWidth) / (amounts.size + 1)
        return leftPad + gap + index * (barWidth + gap) + barWidth / 2f
    }

    /** Puts the finger down in the middle of column [index]'s plot area. */
    private fun pressColumn(index: Int) {
        chartNode().performTouchInput {
            down(Offset(columnCenterX(index), px(chartTopPad + chartPlotHeight / 2f)))
        }
    }

    private fun release() {
        chartNode().performTouchInput { up() }
    }

    @Test
    fun pressing_a_column_holds_its_amount_chip_and_releasing_hides_it() {
        launchDashboard()
        bringTrendChartIntoView()

        pressColumn(0)
        composeRule.onNodeWithTag("trend-value-chip").assertIsDisplayed()
        composeRule.onNodeWithText("€42.50").assertIsDisplayed()
        // Amount only: the old "Month · €amount" readout line never shows.
        val oldReadout = "${Dates.monthLabel(fixtureMonths()[0].toString())} · €42.50"
        composeRule.onAllNodesWithText(oldReadout).assertCountEquals(0)
        // The chip floats over the bar's column, well below the chart's
        // top edge (only a near-full-height bar clamps up there).
        val chipTop = composeRule.onNodeWithText("€42.50").fetchSemanticsNode()
            .boundsInRoot.top - chartNode().fetchSemanticsNode().boundsInRoot.top
        assertTrue("chip should float above the bar, got top=$chipTop", chipTop > px(chartTopPad))

        release()
        composeRule.onNodeWithTag("trend-value-chip").assertDoesNotExist()
        composeRule.onNodeWithText("€42.50").assertDoesNotExist()
        // No readout line persists after the release either.
        composeRule.onAllNodesWithText(oldReadout).assertCountEquals(0)
    }

    @Test
    fun a_zero_month_stub_shows_its_0_00_chip_the_same_way() {
        launchDashboard()
        bringTrendChartIntoView()

        pressColumn(1)
        composeRule.onNodeWithText("€0.00").assertIsDisplayed()

        release()
        composeRule.onNodeWithText("€0.00").assertDoesNotExist()
    }

    @Test
    fun a_full_height_bars_chip_is_clamped_inside_the_charts_top_edge() {
        launchDashboard()
        bringTrendChartIntoView()

        pressColumn(2)
        composeRule.onNodeWithText("€120.00").assertIsDisplayed()
        // The tallest bar's top sits at the plot's top edge, so its chip
        // would start above the chart: it clamps flush inside the chart's
        // top edge — the chip's own 2 dp vertical padding included — and
        // never clips above it.
        val chipTop = composeRule.onNodeWithText("€120.00").fetchSemanticsNode()
            .boundsInRoot.top - chartNode().fetchSemanticsNode().boundsInRoot.top
        assertTrue("chip should be clamped at the chart's top, got top=$chipTop", chipTop >= 0f)
        assertTrue("chip should start at the chart's top edge, got top=$chipTop", chipTop <= px(4f))

        release()
        composeRule.onNodeWithText("€120.00").assertDoesNotExist()
    }

    @Test
    fun pressing_outside_the_columns_shows_no_chip() {
        launchDashboard()
        bringTrendChartIntoView()

        // The Y axis pad left of the first column belongs to no bar.
        chartNode().performTouchInput {
            down(Offset(px(chartLeftPad / 2f), px(chartTopPad + chartPlotHeight / 2f)))
        }
        composeRule.onNodeWithTag("trend-value-chip").assertDoesNotExist()
        release()
    }
}

/** The in-memory dashboard for the chart tests: a loaded summary and
 * budget (no €0.00 texts elsewhere on screen, so the zero stub's chip is
 * unambiguous) and a trend whose six default-range months carry the test's
 * amounts in range order. */
private class TrendFixtureGateway(private val amounts: List<String>) : DashboardGateway {
    var fetchedFrom: String? = null
    var fetchedTo: String? = null

    override suspend fun fetchSummary(month: String): DashboardSummaryDto =
        DashboardSummaryDto(
            net_worth = "800.00",
            month = month,
            income = "0.00",
            expenses = "0.00",
            expenses_by_category = emptyList(),
            incomes_by_category = emptyList(),
        )

    override suspend fun fetchTrend(kind: TrendKind, fromMonth: String, toMonth: String): TrendDto {
        fetchedFrom = fromMonth
        fetchedTo = toMonth
        val months = generateSequence(YearMonth.parse(fromMonth)) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(YearMonth.parse(toMonth)) }
            .mapIndexed { index, month ->
                MonthBucketDto(month = month.toString(), amount = amounts.getOrElse(index) { "0.00" })
            }
            .toList()
        return TrendDto(from_month = fromMonth, to_month = toMonth, months = months)
    }

    override suspend fun fetchBudget(): BudgetDto =
        BudgetDto(
            month = Dates.currentMonthInRome().toString(),
            monthly_spendable = "61.50",
            daily_allowance = "2.05",
            spendable_today = "12.30",
        )
}
