package com.budjetame.android.ui.recurringincomes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.R
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.ui.common.FieldErrors
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.ui.common.amountFieldError
import com.budjetame.android.ui.common.apiErrorMessage
import com.budjetame.android.ui.common.parseAmountInput
import com.budjetame.android.ui.undo.DeletedEntity
import com.budjetame.android.ui.undo.UndoSink
import com.budjetame.android.ui.recurringcosts.parseIntervalValue
import com.budjetame.android.ui.recurringcosts.sortByNextDue
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
 * and can be changed but never cleared — Save on an empty date reveals a
 * Field Error instead.
 */
data class RecurringIncomeModalState(
    val income: RecurringIncomeDto? = null,
    val name: String = "",
    val amount: String = "",
    val intervalValue: String = "1",
    val intervalUnit: IntervalUnit = IntervalUnit.MONTHS,
    val startDate: String = "",
    /** The server's rejection — the form-level banner, never mixed with
     * [fieldErrors]. */
    val error: UiText? = null,
    /** The last Save attempt's Field Errors (ADR-0009), keyed by
     * [RecurringIncomeFields]: set only by [RecurringIncomesViewModel.submit],
     * never cleared by typing. */
    val fieldErrors: FieldErrors = emptyMap(),
    val submitting: Boolean = false,
    val confirmingFreeze: Boolean = false,
    val freezing: Boolean = false,
    /** True while a frozen definition's delete is in flight (web ADR-0034). */
    val deleting: Boolean = false,
    /** Why the last delete failed — the backend's rule ("3 transactions are
     * linked …") through the error catalogue, or the generic line. */
    val deleteError: UiText? = null,
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
    val occurrencesError: UiText? = null,
    /** The row whose Skip/Un-skip write is in flight — its button disables
     * itself so a double tap cannot fire two writes (the write is
     * idempotent anyway, like the web's `togglingDate`). */
    val togglingDate: String? = null,
) {
    val editing: Boolean get() = income != null

    val busy: Boolean get() = submitting || freezing || deleting

    /** Submit-and-validate (ADR-0009): Save is disabled by in-flight work
     * only — a save, a freeze, or an Occurrence row's Skip/Un-skip (the web
     * form's `submitting || togglingDate !== null`) — never by validation. */
    val canSave: Boolean get() = !busy && togglingDate == null
}

/** The Recurring Income form's Field Error keys (ADR-0009): one per field a
 * message can render under. */
object RecurringIncomeFields {
    const val NAME = "name"
    const val AMOUNT = "amount"
    const val INTERVAL = "interval"
    const val START = "start"
}

/**
 * Submit-and-validate (ADR-0009, mirror of the web RecurringDefinitionForm's
 * `validate()`): one Field Error per wrong field of [form], empty when it
 * may save. The interval is a whole number of units — empty, non-integer,
 * or below 1 is one message. The start date is only mandatory while editing
 * (ADR-0024): at creation an empty one stays allowed, the first Occurrence
 * becoming today.
 */
fun validateRecurringIncomeForm(form: RecurringIncomeModalState): FieldErrors = buildMap {
    if (form.name.isBlank()) put(RecurringIncomeFields.NAME, UiText.of(R.string.field_error_name_empty))
    amountFieldError(form.amount)?.let { put(RecurringIncomeFields.AMOUNT, it) }
    // Digits only, like the web's `^\d+$`: "+3" or "1.5" is not a whole
    // number of units even where toIntOrNull would read it.
    val interval = form.intervalValue.trim()
        .takeIf { raw -> raw.isNotEmpty() && raw.all { it in '0'..'9' } }
        ?.let(::parseIntervalValue)
    if (interval == null || interval < 1) {
        put(RecurringIncomeFields.INTERVAL, UiText.of(R.string.field_error_interval))
    }
    if (form.editing && form.startDate.isBlank()) {
        put(RecurringIncomeFields.START, UiText.of(R.string.field_error_start_date))
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
    /** Where a delete goes: the shell's Undo stack (web ADR-0034). */
    private val undoSink: UndoSink = UndoSink.None,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: UiText? = null,
        val incomes: List<RecurringIncomeDto> = emptyList(),
        val modal: RecurringIncomeModalState? = null,
        val frozenExpanded: Boolean = false,
    ) {
        val activeIncomes: List<RecurringIncomeDto> get() = incomes.filter { !it.frozen }
        val frozenIncomes: List<RecurringIncomeDto> get() = incomes.filter { it.frozen }
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
        // The Occurrences section's own read (web ADR-0026), loaded when an
        // existing definition opens the form — a definition under creation
        // has no id yet, its first Occurrence is only decided at creation.
        loadOccurrences(income.id)
    }

    fun closeModal() {
        _uiState.update { it.copy(modal = null) }
    }

    /**
     * Delete the modal's frozen definition (web ADR-0034): one tap, no
     * confirm step — the Undo entry is the confirmation. The button is
     * always tappable; the backend refuses a definition with linked
     * Transactions and the modal shows its reason inline. A success hands
     * the deleted row to the Undo stack, drops it from the list and closes
     * the modal.
     */
    fun onDeleteTap() {
        val modal = _uiState.value.modal ?: return
        val income = modal.income ?: return
        if (modal.busy || !income.frozen) return
        viewModelScope.launch {
            updateModal { it.copy(deleting = true, deleteError = null) }
            try {
                val deleted = recurringIncomes.deleteRecurringIncome(income.id)
                undoSink.pushDeleted(DeletedEntity.RecurringIncome(deleted))
                _uiState.update { state ->
                    state.copy(
                        incomes = state.incomes.filterNot { it.id == income.id },
                        // Only this definition's modal closes: one opened
                        // on another row meanwhile stays.
                        modal = state.modal?.takeUnless { it.income?.id == income.id },
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        deleting = false,
                        // A delete has no name to conflict on: any status
                        // but a cataloged 422 rule is the generic line.
                        deleteError = apiErrorMessage(
                            error,
                            UiText.of(R.string.errors_recurring_income_delete),
                            UiText.of(R.string.errors_recurring_income_delete),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(deleting = false, deleteError = UiText.of(R.string.errors_recurring_income_delete))
                }
            }
        }
    }

    fun toggleFrozenExpanded() {
        _uiState.update { it.copy(frozenExpanded = !it.frozenExpanded) }
    }

    fun onNameChange(value: String) =
        updateModal { it.copy(name = value.take(NAME_MAX_LENGTH), error = null) }

    fun onAmountChange(value: String) = updateModal { it.copy(amount = value, error = null) }

    fun onIntervalValueChange(value: String) =
        updateModal { it.copy(intervalValue = value, error = null) }

    fun onIntervalUnitChange(value: IntervalUnit) =
        updateModal { it.copy(intervalUnit = value, error = null) }

    fun onStartDateChange(value: String) = updateModal { it.copy(startDate = value, error = null) }

    /**
     * Submit-and-validate (ADR-0009): validate first — any Field Error is
     * stored on the modal and nothing is written; a valid form clears them
     * and saves exactly as before. Only the next Save refreshes the errors.
     */
    fun submit() {
        val modal = _uiState.value.modal ?: return
        if (!modal.canSave) return
        val fieldErrors = validateRecurringIncomeForm(modal)
        updateModal { it.copy(fieldErrors = fieldErrors) }
        if (fieldErrors.isNotEmpty()) return
        if (modal.editing) update(modal) else create(modal)
    }

    fun onFreezeTap() {
        val modal = _uiState.value.modal ?: return
        val income = modal.income ?: return
        if (modal.busy || income.frozen) return
        if (!modal.confirmingFreeze) {
            updateModal { it.copy(confirmingFreeze = true, error = null) }
            return
        }
        viewModelScope.launch {
            updateModal { it.copy(freezing = true, error = null) }
            try {
                val frozen = recurringIncomes.freezeRecurringIncome(income.id)
                _uiState.update { state ->
                    state.copy(
                        incomes = sortByNextDue(
                            state.incomes.map { if (it.id == frozen.id) frozen else it },
                        ),
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        confirmingFreeze = false,
                        freezing = false,
                        error = apiErrorMessage(
                            error,
                            CONFLICT_MESSAGE,
                            UiText.of(R.string.errors_recurring_income_freeze),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(
                        confirmingFreeze = false,
                        freezing = false,
                        error = UiText.of(R.string.errors_recurring_income_freeze),
                    )
                }
            }
        }
    }

    fun onUnfreezeTap() {
        val modal = _uiState.value.modal ?: return
        val income = modal.income ?: return
        if (modal.busy || modal.income?.frozen != true) return
        viewModelScope.launch {
            updateModal { it.copy(freezing = true, error = null) }
            try {
                val unfrozen = recurringIncomes.unfreezeRecurringIncome(income.id)
                _uiState.update { state ->
                    state.copy(
                        incomes = sortByNextDue(
                            state.incomes.map { if (it.id == unfrozen.id) unfrozen else it },
                        ),
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        freezing = false,
                        error = apiErrorMessage(
                            error,
                            CONFLICT_MESSAGE,
                            UiText.of(R.string.errors_recurring_income_unfreeze),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(
                        freezing = false,
                        error = UiText.of(R.string.errors_recurring_income_unfreeze),
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
                            occurrencesError = UiText.of(R.string.recurring_occurrence_update_error),
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
                    state.copy(modal = modal.copy(occurrencesError = UiText.of(R.string.recurring_occurrences_load_error)))
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
                            error,
                            CONFLICT_MESSAGE,
                            UiText.of(R.string.errors_recurring_income_create),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = UiText.of(R.string.errors_recurring_income_create)) }
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
                            error,
                            CONFLICT_MESSAGE,
                            UiText.of(R.string.errors_recurring_income_save),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = UiText.of(R.string.errors_recurring_income_save)) }
            }
        }
    }

    private fun draftOf(modal: RecurringIncomeModalState): RecurringIncomeDraft =
        RecurringIncomeDraft(
            name = modal.name.trim(),
            // The tolerant Amount Input's canonical value, never the typed
            // text: "17,5" goes out as "17.5". Validation ran first, so the
            // amount parses here.
            amount = parseAmountInput(modal.amount)!!.toPlainString(),
            intervalValue = parseIntervalValue(modal.intervalValue) ?: 0,
            intervalUnit = modal.intervalUnit,
            // Empty only ever at creation: "start today" needs no typing,
            // the backend sets the creation day (ADR-0024). An edit's empty
            // date is a Field Error, so an update never sends null.
            startDate = modal.startDate.trim().ifEmpty { null },
        )

    /**
     * Fetch the list. A failed background refetch keeps the held data on
     * screen (ADR-0002); a failure with nothing to show surfaces the error.
     */
    private suspend fun reload() {
        try {
            val loaded = sortByNextDue(recurringIncomes.fetchRecurringIncomes(includeFrozen = true))
            _uiState.update {
                it.copy(incomes = loaded, loadError = null, loading = false)
            }
        } catch (_: Exception) {
            _uiState.update { state ->
                if (state.incomes.isEmpty()) {
                    state.copy(loadError = UiText.of(R.string.recurring_incomes_load_error), loading = false)
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
        val CONFLICT_MESSAGE: UiText = UiText.of(R.string.errors_recurring_income_conflict)
    }
}
