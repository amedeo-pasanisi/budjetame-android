package com.budjetame.android.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.CategorySliceDto
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.DashboardSummaryDto
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
 * The Dashboard's state machine (ticket #17), ported from the web app's
 * DashboardScreen: Net Worth plus the reference month's Income/Expense
 * totals and its category pie, toggled between Expenses and Incomes. The
 * month defaults to the current Europe/Rome one; the previous/next arrows
 * refetch the summary for the neighbouring month, and every month-driven
 * card titles itself with the loaded summary's month, never the requested
 * one (US27). Data is refetched in the background when the global data
 * version bumps (ADR-0002).
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
    ) {
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
    }

    private val _uiState = MutableStateFlow(UiState(requestedMonth = currentMonth()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // ADR-0002: the transport bumps the data version after every write,
        // and this screen refetches in the background. The first emission
        // (the current version) is the initial load.
        viewModelScope.launch {
            DataVersion.version.collect { reload() }
        }
    }

    fun previousMonth() = showMonth(_uiState.value.requestedMonth.minusMonths(1))

    fun nextMonth() = showMonth(_uiState.value.requestedMonth.plusMonths(1))

    fun onPieSideChange(side: PieSide) {
        _uiState.update { it.copy(pieSide = side) }
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadError = null) }
            reload()
        }
    }

    private fun showMonth(month: YearMonth) {
        _uiState.update { it.copy(requestedMonth = month) }
        viewModelScope.launch { reload() }
    }

    /**
     * Fetch the summary for the currently requested month. A response is
     * only applied when it still matches the requested month (a rapid
     * previous/next walk must never let a slow response overwrite the
     * newest one, US27). A failed month change reverts the selector to the
     * held month so the stale data never sits under the wrong title; a
     * failure with nothing to show surfaces the error.
     */
    private suspend fun reload() {
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
}
