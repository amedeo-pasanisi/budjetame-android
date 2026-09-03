package com.budjetame.android.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.data.api.CategorySliceDto
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.util.Dates
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The pie card's two sides, selected with the Expenses/Incomes toggle. */
enum class PieSide { EXPENSE, INCOME }

/**
 * The Dashboard's state machine (tickets #17, #18), ported from the web
 * app's DashboardScreen: Net Worth plus the reference month's category pie
 * — the pie card owns its month picker and its Expenses/Incomes toggle
 * (web parity), one summary response serving the toggle's two pies — the
 * monthly trend chart with its own Expenses/Incomes toggle over a
 * user-picked From/To month range (T12, US28); and the Budget card — the
 * current Europe/Rome month's Monthly Spendable, Daily Allowance, and
 * Spendable Today, rendered raw from GET /dashboard/budget (negative
 * Spendable Today is the card's job to floor, ADR-0012 semantics). The
 * month defaults to the current Europe/Rome one; the pie card's Month
 * picker refetches the summary for the picked month, and the card titles
 * itself with the loaded summary's month, never the requested one (US27).
 * Data is refetched in the background when the global data version bumps
 * (ADR-0002).
 */
class DashboardViewModel(
    private val dashboard: DashboardGateway,
    private val currentMonth: () -> YearMonth = { Dates.currentMonthInRome() },
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val requestedMonth: YearMonth,
        val summary: DashboardSummaryDto? = null,
        val pieSide: PieSide = PieSide.EXPENSE,
        val trendKind: TrendKind = TrendKind.EXPENSE,
        val trendFrom: YearMonth,
        val trendTo: YearMonth,
        val trend: LoadedTrend? = null,
        val trendError: String? = null,
        val budget: BudgetDto? = null,
        val budgetError: String? = null,
    ) {
        /** A loaded trend tagged with the side it was fetched for — a stale
         * trend must never render under the toggle's current side (US28). */
        data class LoadedTrend(val kind: TrendKind, val data: TrendDto)

        /** The month the held summary describes; null before the first load. */
        val loadedMonth: YearMonth? get() = summary?.let { YearMonth.parse(it.month) }

        /**
         * True when the held summary is the requested month's — the
         * month-driven cards show "Loading…" until it is true again.
         */
        val monthInSync: Boolean get() = loadedMonth == requestedMonth

        /** The pie the toggle selects, from the one summary response. */
        val pieSlices: List<CategorySliceDto>
            get() = summary?.let {
                when (pieSide) {
                    PieSide.EXPENSE -> it.expenses_by_category
                    PieSide.INCOME -> it.incomes_by_category
                }
            } ?: emptyList()

        /** The pie center label: the month's total for the pie's side. */
        val pieTotal: String
            get() = summary?.let {
                when (pieSide) {
                    PieSide.EXPENSE -> it.expenses
                    PieSide.INCOME -> it.income
                }
            } ?: "0.00"

        /** True when the held trend matches the requested side and range —
         * the chart only renders then. */
        val trendInSync: Boolean
            get() = trend?.let {
                it.kind == trendKind &&
                    it.data.from_month == trendFrom.toString() &&
                    it.data.to_month == trendTo.toString()
            } == true
    }

    private val _uiState = MutableStateFlow(
        UiState(
            requestedMonth = currentMonth(),
            // The web app's default trend range: the current month minus
            // five months through the current month.
            trendFrom = currentMonth().minusMonths(5),
            trendTo = currentMonth(),
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // ADR-0002: the transport bumps the data version after every write,
        // and this screen refetches in the background. The first emission
        // (the current version) is the initial load.
        viewModelScope.launch {
            DataVersion.version.collect { reload() }
        }
    }

    /** The pie card's month picker (web parity): picking a month refetches
     * the summary for it. Only the summary is month-driven: the Budget is
     * current-month-only and the trend has its own range, so a month change
     * refetches the summary alone (exactly like the web app's effects). */
    fun onPieMonthChange(month: YearMonth) {
        _uiState.update { it.copy(requestedMonth = month) }
        viewModelScope.launch { reloadSummary() }
    }

    fun onPieSideChange(side: PieSide) {
        _uiState.update { it.copy(pieSide = side) }
    }

    fun onTrendKindChange(kind: TrendKind) {
        _uiState.update { it.copy(trendKind = kind) }
        viewModelScope.launch { reloadTrend() }
    }

    /** The web app's T12 rule: picking From after To swaps the two — the
     * user's intent was a range between the two months, never a reversed
     * request the endpoint would 422. */
    fun onTrendFromChange(month: YearMonth) {
        val state = _uiState.value
        val (from, to) =
            if (month.isAfter(state.trendTo)) state.trendTo to month else month to state.trendTo
        _uiState.update { it.copy(trendFrom = from, trendTo = to) }
        viewModelScope.launch { reloadTrend() }
    }

    /** The mirror rule: picking To before From swaps the two. */
    fun onTrendToChange(month: YearMonth) {
        val state = _uiState.value
        val (from, to) =
            if (month.isBefore(state.trendFrom)) month to state.trendFrom else state.trendFrom to month
        _uiState.update { it.copy(trendFrom = from, trendTo = to) }
        viewModelScope.launch { reloadTrend() }
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadError = null) }
            reload()
        }
    }

    /** Refetch every card: the summary for the requested month, the current
     * month's Budget, and the trend for the requested side and range. Each
     * card fails into its own error state, never blocking the others. */
    private suspend fun reload() {
        reloadSummary()
        reloadBudget()
        reloadTrend()
    }

    /**
     * Fetch the summary for the currently requested month. A response is
     * only applied when it still matches the requested month (a rapid
     * previous/next walk must never let a slow response overwrite the
     * newest one, US27). A failed month change reverts the selector to the
     * held month so the stale data never sits under the wrong title; a
     * failure with nothing to show surfaces the error.
     */
    private suspend fun reloadSummary() {
        val month = _uiState.value.requestedMonth
        try {
            val loaded = dashboard.fetchSummary(month.toString())
            _uiState.update { state ->
                if (state.requestedMonth == month) {
                    state.copy(summary = loaded, loadError = null, loading = false)
                } else {
                    state
                }
            }
        } catch (_: Exception) {
            _uiState.update { state ->
                if (state.requestedMonth != month) {
                    state
                } else if (state.summary == null) {
                    state.copy(loadError = "Could not load your dashboard.", loading = false)
                } else {
                    state.copy(requestedMonth = state.loadedMonth ?: month, loading = false)
                }
            }
        }
    }

    /**
     * Fetch the Budget card's frame. The Budget is current-month-only (web
     * issue #66): unlike the summary, the endpoint takes no month parameter
     * and the card ignores the pie's month selector, so a month change never
     * refetches it. A failed load must never look like an empty Budget, so
     * the error is its own state.
     */
    private suspend fun reloadBudget() {
        _uiState.update { it.copy(budgetError = null) }
        try {
            val loaded = dashboard.fetchBudget()
            _uiState.update { it.copy(budget = loaded, budgetError = null) }
        } catch (_: Exception) {
            _uiState.update { it.copy(budgetError = "Could not load the budget.") }
        }
    }

    /**
     * Fetch the trend for the currently requested side and range. A
     * response is only applied when it still matches the requested side and
     * range (a rapid toggle/picker walk must never let a slow response
     * overwrite the newest one, US28). The loaded trend keeps its own kind
     * and range, so a stale response can never render under the new title.
     */
    private suspend fun reloadTrend() {
        val state = _uiState.value
        val kind = state.trendKind
        val from = state.trendFrom.toString()
        val to = state.trendTo.toString()
        _uiState.update { it.copy(trendError = null) }
        try {
            val loaded = dashboard.fetchTrend(kind, from, to)
            _uiState.update { current ->
                if (current.trendKind == kind &&
                    current.trendFrom.toString() == from &&
                    current.trendTo.toString() == to
                ) {
                    current.copy(trend = UiState.LoadedTrend(kind, loaded), trendError = null)
                } else {
                    current
                }
            }
        } catch (_: Exception) {
            _uiState.update { current ->
                if (current.trendKind == kind &&
                    current.trendFrom.toString() == from &&
                    current.trendTo.toString() == to
                ) {
                    current.copy(trendError = "Could not load the trend.")
                } else {
                    current
                }
            }
        }
    }
}
