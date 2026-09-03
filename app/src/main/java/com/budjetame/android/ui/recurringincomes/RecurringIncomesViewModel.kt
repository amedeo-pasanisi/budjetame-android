package com.budjetame.android.ui.recurringincomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.apiErrorMessage
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.ui.recurringcosts.parseIntervalValue
import com.budjetame.android.ui.recurringcosts.sortByNextDue
import com.budjetame.android.ui.transactions.parseAmount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The create/edit/delete Recurring Income modal's draft (null = modal closed)
 * — one modal serves create and edit, like the Wallets and Categories
 * forms, mirroring the Recurring Cost modal's draft (ADR-0011). `startDate`
 * is "" when unset — only possible at creation, where empty means "start
 * today" (the backend sets it to the creation day): an existing definition
 * always carries one (ADR-0024), so while editing the field is prefilled
 * and can be changed but never cleared — the save gate below blocks an
 * empty date.
 */
data class RecurringIncomeModalState(
    val income: RecurringIncomeDto? = null,
    val name: String = "",
    val amount: String = "",
    val intervalValue: String = "1",
    val intervalUnit: IntervalUnit = IntervalUnit.MONTHS,
    val startDate: String = "",
    val error: String? = null,
    val submitting: Boolean = false,
    val confirmingDelete: Boolean = false,
    val deleting: Boolean = false,
) {
    val editing: Boolean get() = income != null

    val busy: Boolean get() = submitting || deleting

    /** The web form's save gate (ADR-0024): a non-blank name, an amount
     * over €0, a whole interval of at least 1, and — the start date is
     * only optional at creation (empty = today); an existing definition
     * always carries one, so an empty date blocks an edit's save. */
    val canSubmit: Boolean
        get() {
            if (busy) return false
            val interval = parseIntervalValue(intervalValue) ?: return false
            if (interval < 1) return false
            if (parseAmount(amount) == null) return false
            if (editing && startDate.isBlank()) return false
            return name.isNotBlank()
        }
}

/**
 * The Recurring Incomes screen's state machine (ticket #23, extended for
 * the Skip/Un-skip button by ticket #24), mirroring the Recurring Costs
 * side (web issue #60, ADR-0011): the list of definitions sorted by next
 * due date — each row naming the amount, the interval, the next due date,
 * the next Unpaid Occurrence date, the Backlog badge, and the Overdue mark
 * — plus create/edit/delete in a modal, with the names unique
 * case-insensitively (409 → the web's exact message), and the per-row
 * Skip/Un-skip button (ADR-0016), mirroring the Costs side. Data is
 * refetched in the background when the global data version bumps
 * (ADR-0002), so a link paid or severed elsewhere re-renders the derived
 * state.
 */
class RecurringIncomesViewModel(
    private val recurringIncomes: RecurringIncomeGateway,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val incomes: List<RecurringIncomeDto> = emptyList(),
        /** The definition whose Skip/Un-skip button is in flight — the
         * button disables itself so a double tap cannot flip the state twice
         * (skip then un-skip), like the web screen's `togglingId`. */
        val togglingId: Int? = null,
        /** A failed toggle's message, shown above the list — the held rows
         * stay on screen (the web screen's inline load-error paragraph). */
        val actionError: String? = null,
        val modal: RecurringIncomeModalState? = null,
    ) {
        /** The summary line's counts (web issue #62): only shown when there
         * is at least one income — the empty state already answers the
         * screen for a definition-less Account. */
        val overdueCount: Int get() = incomes.count { it.overdue }
        val unpaidCount: Int get() = incomes.sumOf { it.backlog_count }
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
        _uiState.update { it.copy(modal = RecurringIncomeModalState()) }
    }

    fun openEdit(income: RecurringIncomeDto) {
        _uiState.update {
            it.copy(
                modal = RecurringIncomeModalState(
                    income = income,
                    name = income.name,
                    amount = income.amount,
                    intervalValue = income.interval_value.toString(),
                    intervalUnit = income.interval_unit,
                    startDate = income.start_date,
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

    fun submit() {
        val modal = _uiState.value.modal ?: return
        if (!modal.canSubmit) return
        if (modal.editing) update(modal) else create(modal)
    }

    fun onDeleteTap() {
        val modal = _uiState.value.modal ?: return
        val income = modal.income ?: return
        if (modal.busy) return
        if (!modal.confirmingDelete) {
            updateModal { it.copy(confirmingDelete = true, error = null) }
            return
        }
        viewModelScope.launch {
            updateModal { it.copy(deleting = true, error = null) }
            try {
                recurringIncomes.deleteRecurringIncome(income.id)
                _uiState.update { state ->
                    state.copy(
                        incomes = state.incomes.filterNot { it.id == income.id },
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
                            "Could not delete the recurring income.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(
                        confirmingDelete = false,
                        deleting = false,
                        error = "Could not delete the recurring income.",
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
     * The Skip/Un-skip button (ADR-0016), mirroring the Costs side: the
     * backend flips the oldest Unpaid Occurrence — skipping it, or
     * un-skipping the oldest Skipped one once the whole Backlog is excused
     * — and returns the refreshed definition, which replaces the row in
     * place and re-sorts, so the badge, the Overdue mark, the dates, and the
     * button's own label all re-render from the response (the web screen
     * swaps the card the same way). A double tap on the same row cannot
     * flip the state twice (skip then un-skip): the button disables while
     * its own toggle is in flight. The toggle is a write, so its
     * data-version bump also refetches the list in the background; the
     * refetch and the swap carry the same refreshed definition, so the row
     * never flickers back (ADR-0002).
     */
    fun toggleSkip(income: RecurringIncomeDto) {
        if (_uiState.value.togglingId == income.id) return
        viewModelScope.launch {
            // The per-row in-flight guard, checked again under the launch:
            // on a confined Main dispatcher two taps in the same frame could
            // both pass the check above before the state lands.
            if (_uiState.value.togglingId == income.id) return@launch
            _uiState.update { it.copy(togglingId = income.id, actionError = null) }
            try {
                val toggled = recurringIncomes.toggleSkipRecurringIncome(income.id)
                _uiState.update { state ->
                    state.copy(
                        incomes = sortByNextDue(
                            state.incomes.map { if (it.id == toggled.id) toggled else it },
                        ),
                        togglingId = null,
                    )
                }
            } catch (_: Exception) {
                // The web screen's toggle-failure message: the rows stay on
                // screen (the list still answers), only the action failed.
                _uiState.update {
                    it.copy(togglingId = null, actionError = "Could not update your recurring incomes.")
                }
            }
        }
    }

    private fun create(modal: RecurringIncomeModalState) {
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val created = recurringIncomes.createRecurringIncome(draftOf(modal))
                _uiState.update { state ->
                    state.copy(
                        // In place: the next-due order is the screen's one
                        // order (web sortByNextDue), so an upsert re-sorts.
                        // The id-guard drops a duplicate when the write's
                        // own data-version bump refetched the list first.
                        incomes = sortByNextDue(
                            state.incomes.filterNot { it.id == created.id } + created,
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
                            "Could not create the recurring income.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not create the recurring income.") }
            }
        }
    }

    private fun update(modal: RecurringIncomeModalState) {
        val income = modal.income ?: return
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val saved = recurringIncomes.updateRecurringIncome(income.id, draftOf(modal))
                _uiState.update { state ->
                    state.copy(
                        incomes = sortByNextDue(
                            state.incomes.map { if (it.id == saved.id) saved else it },
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
                            "Could not save the recurring income.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not save the recurring income.") }
            }
        }
    }

    private fun draftOf(modal: RecurringIncomeModalState): RecurringIncomeDraft =
        RecurringIncomeDraft(
            name = modal.name.trim(),
            amount = modal.amount.trim(),
            intervalValue = parseIntervalValue(modal.intervalValue) ?: 0,
            intervalUnit = modal.intervalUnit,
            // Empty only ever at creation: "start today" needs no typing,
            // the backend sets the creation day (ADR-0024). An edit's gate
            // keeps the date non-empty, so an update never sends null.
            startDate = modal.startDate.trim().ifEmpty { null },
        )

    /**
     * Fetch the list. A failed background refetch keeps the held data on
     * screen (ADR-0002); a failure with nothing to show surfaces the error.
     */
    private suspend fun reload() {
        try {
            val loaded = sortByNextDue(recurringIncomes.fetchRecurringIncomes())
            _uiState.update {
                it.copy(incomes = loaded, loadError = null, actionError = null, loading = false)
            }
        } catch (_: Exception) {
            _uiState.update { state ->
                if (state.incomes.isEmpty()) {
                    state.copy(loadError = "Could not load your recurring incomes.", loading = false)
                } else {
                    state.copy(loading = false)
                }
            }
        }
    }

    private fun updateModal(transform: (RecurringIncomeModalState) -> RecurringIncomeModalState) {
        _uiState.update { state ->
            state.modal?.let { state.copy(modal = transform(it)) } ?: state
        }
    }

    companion object {
        /** The Name field's cap (CONTEXT.md: names up to 80 characters). */
        const val NAME_MAX_LENGTH = 80

        /** The web's exact duplicate-name message (409 contract). */
        const val CONFLICT_MESSAGE = "A recurring income with this name already exists."
    }
}
