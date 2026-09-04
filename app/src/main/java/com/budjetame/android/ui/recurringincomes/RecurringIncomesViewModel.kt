package com.budjetame.android.ui.recurringincomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RecurringOccurrenceDto
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
    /** The Occurrences section's rows (web ADR-0026): their own read, null
     * while the read is still in flight. Edit mode only — a definition
     * under creation has no id yet, so a create never fetches. The rows
     * are the section's whole state in the server's order (the next
     * incoming Unpaid row on top, then newest-first down to the oldest);
     * a row toggle below replaces them with the write's refreshed read.
     * A client never re-sorts the list. */
    val occurrences: List<RecurringOccurrenceDto>? = null,
    /** A failed occurrences read or row toggle's message — the web
     * section's inline error line. Cleared by the next toggle press. */
    val occurrencesError: String? = null,
    /** The row whose Skip/Un-skip write is in flight — its button disables
     * itself so a double tap cannot fire two writes (the write is
     * idempotent anyway, like the web's `togglingDate`). */
    val togglingDate: String? = null,
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
 * the ledger-jump card shape by ticket #46), mirroring the Recurring Costs
 * side (web issue #60, ADR-0011): the list of definitions sorted by next
 * due date — each row naming the amount, the interval, the next due date,
 * the next Unpaid Occurrence date, and the red "N unpaid" Backlog badge
 * (the one Backlog signal, web ADR-0025 / ticket #45)
 * — plus create/edit/delete in a modal, with the names unique
 * case-insensitively (409 → the web's exact message).
 * The card Skip/Un-skip button is gone (web ADR-0026): the whole-row tap
 * is the ledger jump to the definition's linked Transactions, the ✎
 * button opens the edit modal, and the modal's own Occurrences section
 * carries the per-Occurrence Skip/Un-skip controls — its read loads when
 * an existing definition opens the modal, and each row toggle states the
 * row's skipped state and swaps in the refreshed read. A toggle is a
 * write, so its data-version bump also refetches the list in the
 * background (ADR-0002): the badge and the dates on the cards behind
 * re-derive from the stored skips. Data is
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
        val modal: RecurringIncomeModalState? = null,
    )

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
        // The Occurrences section's own read (web ADR-0026), loaded when an
        // existing definition opens the form — a definition under creation
        // has no id yet, its first Occurrence is only decided at creation.
        loadOccurrences(income.id)
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
     * One row's Skip/Un-skip (web ADR-0026), mirroring the Costs side:
     * state the row's skipped state — the response is the refreshed read,
     * so the section swaps its rows in without a second fetch. The write
     * also refreshes the definition's derived state: the data-version bump
     * refetches the list behind the modal (ADR-0002), so the badge and the
     * dates re-derive from the stored skips. A double tap on the same row
     * cannot fire two writes: the row disables while its own toggle is in
     * flight (the write is idempotent anyway). The response only lands if
     * the modal still edits the same definition — a write that completes
     * after the modal closed and reopened on another income never swaps
     * its rows in.
     */
    fun toggleOccurrence(row: RecurringOccurrenceDto) {
        val modal = _uiState.value.modal ?: return
        val income = modal.income ?: return
        if (modal.togglingDate == row.date) return
        viewModelScope.launch {
            // The per-row in-flight guard, checked again under the launch:
            // on a confined Main dispatcher two taps in the same frame could
            // both pass the check above before the state lands.
            if (_uiState.value.modal?.togglingDate == row.date) return@launch
            updateModal { it.copy(togglingDate = row.date, occurrencesError = null) }
            try {
                val rows = recurringIncomes.setOccurrenceSkipped(income.id, row.date, skipped = !row.skipped)
                _uiState.update { state ->
                    val open = state.modal
                    if (open == null || open.income?.id != income.id) return@update state
                    state.copy(modal = open.copy(occurrences = rows, togglingDate = null))
                }
            } catch (_: Exception) {
                // The web section's toggle-failure message: the rows stay on
                // screen, only the action failed.
                _uiState.update { state ->
                    val open = state.modal
                    if (open == null || open.income?.id != income.id) return@update state
                    state.copy(
                        modal = open.copy(
                            togglingDate = null,
                            occurrencesError = "Could not update the occurrence.",
                        ),
                    )
                }
            }
        }
    }

    /**
     * The Occurrences section's read (web ADR-0026), mirroring the Costs
     * side: every non-Paid Occurrence with its skipped state, newest
     * first. A failure shows the section's inline error and keeps the
     * modal usable — closing and reopening the modal refetches, like the
     * web. The response only lands if the modal still edits the same
     * definition: a closed or re-opened modal (another income) never
     * receives a stale read.
     */
    private fun loadOccurrences(id: Int) {
        viewModelScope.launch {
            try {
                val rows = recurringIncomes.fetchOccurrences(id)
                _uiState.update { state ->
                    val modal = state.modal
                    if (modal == null || modal.income?.id != id) return@update state
                    state.copy(modal = modal.copy(occurrences = rows))
                }
            } catch (_: Exception) {
                _uiState.update { state ->
                    val modal = state.modal
                    if (modal == null || modal.income?.id != id) return@update state
                    state.copy(modal = modal.copy(occurrencesError = "Could not load the occurrences."))
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
                it.copy(incomes = loaded, loadError = null, loading = false)
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
