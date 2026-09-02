package com.budjetame.android.ui.recurringcosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.apiErrorMessage
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.ui.transactions.parseAmount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The create/edit/delete Recurring Cost modal's draft (null = modal closed)
 * — one modal serves create and edit, like the Wallets and Categories
 * forms. `startDate` is "" when unset (the creation date); `dueDay` and
 * `dueMonth` are the due-date override draft — null = unset — whose shape
 * follows the interval unit: a day-of-month for months, a month+day pair
 * for years, nothing for days/weeks (the fields render only for the unit
 * that carries them, and a stale pick is dropped at submit, never sent).
 */
data class RecurringCostModalState(
    val cost: RecurringCostDto? = null,
    val name: String = "",
    val amount: String = "",
    val intervalValue: String = "1",
    val intervalUnit: IntervalUnit = IntervalUnit.MONTHS,
    val startDate: String = "",
    val dueDay: Int? = null,
    val dueMonth: Int? = null,
    val error: String? = null,
    val submitting: Boolean = false,
    val confirmingDelete: Boolean = false,
    val deleting: Boolean = false,
) {
    val editing: Boolean get() = cost != null

    val busy: Boolean get() = submitting || deleting

    /** The web form's save gate: a non-blank name, an amount over €0, a
     * whole interval of at least 1, and a year override never half-picked
     * (a half pair blocks the save instead of silently dropping it). */
    val canSubmit: Boolean
        get() {
            if (busy) return false
            val interval = parseIntervalValue(intervalValue) ?: return false
            if (interval < 1) return false
            if (parseAmount(amount) == null) return false
            if (intervalUnit == IntervalUnit.YEARS &&
                yearOverrideIncomplete(dueDay, dueMonth)
            ) {
                return false
            }
            return name.isNotBlank()
        }
}

/**
 * The Recurring Costs screen's state machine (ticket #22, extended for the
 * Skip/Un-skip button by ticket #24), ported from the web app's
 * RecurringCostsScreen + RecurringCostForm (web issues #56/#58): the list
 * of definitions sorted by next due date — each row naming the amount, the
 * interval, the next due date, the next Unpaid Occurrence date, the Backlog
 * badge, and the Overdue mark — plus create/edit/delete in a modal, with
 * the names unique case-insensitively (409 → the web's exact message), and
 * the per-row Skip/Un-skip button (ADR-0016): the backend flips the front
 * of the queue and the response swaps the row in, so the badge, the Overdue
 * mark, the dates, and the button's own label re-render from the refreshed
 * definition. Data is refetched in the background when the global data
 * version bumps (ADR-0002), so a link paid or severed elsewhere re-renders
 * the derived state.
 */
class RecurringCostsViewModel(private val recurringCosts: RecurringCostGateway) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val costs: List<RecurringCostDto> = emptyList(),
        /** The definition whose Skip/Un-skip button is in flight — the
         * button disables itself so a double tap cannot flip the state twice
         * (skip then un-skip), like the web screen's `togglingId`. */
        val togglingId: Int? = null,
        /** A failed toggle's message, shown above the list — the held rows
         * stay on screen (the web screen's inline load-error paragraph). */
        val actionError: String? = null,
        val modal: RecurringCostModalState? = null,
    ) {
        /** The summary line's counts (web issue #58): only shown when there
         * is at least one cost — the empty state already answers the
         * screen for a definition-less Account. */
        val overdueCount: Int get() = costs.count { it.overdue }
        val unpaidCount: Int get() = costs.sumOf { it.backlog_count }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // ADR-0002: the transport bumps the data version after every write,
        // and this screen refetches in the background — a link created or
        // paid on the Transactions tab re-derives the badges and the dates.
        viewModelScope.launch {
            DataVersion.version.collect { reload() }
        }
    }

    fun openCreate() {
        _uiState.update { it.copy(modal = RecurringCostModalState()) }
    }

    fun openEdit(cost: RecurringCostDto) {
        _uiState.update {
            it.copy(
                modal = RecurringCostModalState(
                    cost = cost,
                    name = cost.name,
                    amount = cost.amount,
                    intervalValue = cost.interval_value.toString(),
                    intervalUnit = cost.interval_unit,
                    startDate = cost.start_date ?: "",
                    dueDay = cost.due_day,
                    dueMonth = cost.due_month,
                ),
            )
        }
    }

    fun closeModal() {
        _uiState.update { it.copy(modal = null) }
    }

    fun onNameChange(value: String) =
        updateModal { it.copy(name = value.take(NAME_MAX_LENGTH), error = null) }

    fun onAmountChange(value: String) = updateModal { it.copy(amount = value, error = null) }

    fun onIntervalValueChange(value: String) =
        updateModal { it.copy(intervalValue = value, error = null) }

    fun onIntervalUnitChange(value: IntervalUnit) =
        updateModal { it.copy(intervalUnit = value, error = null) }

    fun onStartDateChange(value: String) = updateModal { it.copy(startDate = value, error = null) }

    fun onDueDayChange(value: Int?) = updateModal { it.copy(dueDay = value, error = null) }

    fun onDueMonthChange(value: Int?) = updateModal { it.copy(dueMonth = value, error = null) }

    fun submit() {
        val modal = _uiState.value.modal ?: return
        if (!modal.canSubmit) return
        if (modal.editing) update(modal) else create(modal)
    }

    fun onDeleteTap() {
        val modal = _uiState.value.modal ?: return
        val cost = modal.cost ?: return
        if (modal.busy) return
        if (!modal.confirmingDelete) {
            updateModal { it.copy(confirmingDelete = true, error = null) }
            return
        }
        viewModelScope.launch {
            updateModal { it.copy(deleting = true, error = null) }
            try {
                recurringCosts.deleteRecurringCost(cost.id)
                _uiState.update { state ->
                    state.copy(
                        costs = state.costs.filterNot { it.id == cost.id },
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        confirmingDelete = false,
                        deleting = false,
                        error = apiErrorMessage(
                            error.status,
                            CONFLICT_MESSAGE,
                            "Could not delete the recurring cost.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(
                        confirmingDelete = false,
                        deleting = false,
                        error = "Could not delete the recurring cost.",
                    )
                }
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadError = null) }
            reload()
        }
    }

    /**
     * The Skip/Un-skip button (ADR-0016): the backend flips the oldest
     * Unpaid Occurrence — skipping it, or un-skipping the oldest Skipped
     * one once the whole Backlog is excused — and returns the refreshed
     * definition, which replaces the row in place and re-sorts, so the
     * badge, the Overdue mark, the dates, and the button's own label all
     * re-render from the response (the web screen swaps the card the same
     * way). A double tap on the same row cannot flip the state twice (skip
     * then un-skip): the button disables while its own toggle is in flight.
     * The toggle is a write, so its data-version bump also refetches the
     * list in the background; the refetch and the swap carry the same
     * refreshed definition, so the row never flickers back (ADR-0002).
     */
    fun toggleSkip(cost: RecurringCostDto) {
        if (_uiState.value.togglingId == cost.id) return
        viewModelScope.launch {
            // The per-row in-flight guard, checked again under the launch:
            // on a confined Main dispatcher two taps in the same frame could
            // both pass the check above before the state lands.
            if (_uiState.value.togglingId == cost.id) return@launch
            _uiState.update { it.copy(togglingId = cost.id, actionError = null) }
            try {
                val toggled = recurringCosts.toggleSkipRecurringCost(cost.id)
                _uiState.update { state ->
                    state.copy(
                        costs = sortByNextDue(
                            state.costs.map { if (it.id == toggled.id) toggled else it },
                        ),
                        togglingId = null,
                    )
                }
            } catch (_: Exception) {
                // The web screen's toggle-failure message: the rows stay on
                // screen (the list still answers), only the action failed.
                _uiState.update {
                    it.copy(togglingId = null, actionError = "Could not update your recurring costs.")
                }
            }
        }
    }

    private fun create(modal: RecurringCostModalState) {
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val created = recurringCosts.createRecurringCost(draftOf(modal))
                _uiState.update { state ->
                    state.copy(
                        // In place: the next-due order is the screen's one
                        // order (web sortByNextDue), so an upsert re-sorts.
                        // The id-guard drops a duplicate when the write's
                        // own data-version bump refetched the list first.
                        costs = sortByNextDue(
                            state.costs.filterNot { it.id == created.id } + created,
                        ),
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            CONFLICT_MESSAGE,
                            "Could not create the recurring cost.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not create the recurring cost.") }
            }
        }
    }

    private fun update(modal: RecurringCostModalState) {
        val cost = modal.cost ?: return
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val saved = recurringCosts.updateRecurringCost(cost.id, draftOf(modal))
                _uiState.update { state ->
                    state.copy(
                        costs = sortByNextDue(
                            state.costs.map { if (it.id == saved.id) saved else it },
                        ),
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            CONFLICT_MESSAGE,
                            "Could not save the recurring cost.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not save the recurring cost.") }
            }
        }
    }

    private fun draftOf(modal: RecurringCostModalState): RecurringCostDraft {
        val (dueDay, dueMonth) = dueOverrideFor(modal.intervalUnit, modal.dueDay, modal.dueMonth)
        return RecurringCostDraft(
            name = modal.name.trim(),
            amount = modal.amount.trim(),
            intervalValue = parseIntervalValue(modal.intervalValue) ?: 0,
            intervalUnit = modal.intervalUnit,
            startDate = modal.startDate.trim().ifEmpty { null },
            dueDay = dueDay,
            dueMonth = dueMonth,
        )
    }

    /**
     * Fetch the list. A failed background refetch keeps the held data on
     * screen (ADR-0002); a failure with nothing to show surfaces the error.
     */
    private suspend fun reload() {
        try {
            val loaded = sortByNextDue(recurringCosts.fetchRecurringCosts())
            _uiState.update {
                it.copy(costs = loaded, loadError = null, actionError = null, loading = false)
            }
        } catch (_: Exception) {
            _uiState.update { state ->
                if (state.costs.isEmpty()) {
                    state.copy(loadError = "Could not load your recurring costs.", loading = false)
                } else {
                    state.copy(loading = false)
                }
            }
        }
    }

    private fun updateModal(transform: (RecurringCostModalState) -> RecurringCostModalState) {
        _uiState.update { state ->
            state.modal?.let { state.copy(modal = transform(it)) } ?: state
        }
    }

    companion object {
        /** The Name field's cap (CONTEXT.md: names up to 80 characters). */
        const val NAME_MAX_LENGTH = 80

        /** The web app's exact duplicate-name message (409 contract). */
        const val CONFLICT_MESSAGE = "A recurring cost with this name already exists."
    }
}
