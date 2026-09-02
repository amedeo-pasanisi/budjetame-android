package com.budjetame.android.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowRevalidationDto
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.api.apiErrorMessage
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.categories.CategoryModalState
import com.budjetame.android.ui.transactions.WalletFieldTarget
import com.budjetame.android.ui.wallets.WalletModalState
import com.budjetame.android.ui.wallets.normalizeOpeningBalance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Import Draft's phases (web issue #43): pick a file, review its
 * Preview, and the successful-import report. */
enum class ImportPhase { PICK, PREVIEW, DONE }

/**
 * The Import Draft (ticket #26, web issue #43): the unconfirmed import
 * state — the picked file, the parsed Preview, and the row selections —
 * held by the Transactions tab's ViewModel, which survives tab switches
 * (ADR-0002's keep-alive), so leaving the tab and coming back resumes the
 * Preview exactly where it was left. The only discard paths are Cancel,
 * picking another file, and a successful import (then Back); nothing is
 * ever persisted, so a process death loses the draft like a web page
 * reload does. The picked file's bytes sit outside the flow (`pickedFile`
 * below); the flow carries only what the UI renders.
 *
 * The draft machine mirrors the web app's importDraft.ts: every ready row
 * starts selected, a checkbox toggles ready rows, Verification re-validates
 * one edited row (POST /import/validate-row, web issue #44) and flips its
 * status in place — auto-selecting it when it becomes Ready, deselecting it
 * when it stops being one — and the batch Revalidation of problem rows
 * (POST /import/revalidate-rows, web issues #76/#78, ticket #27) flips the
 * rows that now pass, on the Preview's resume or when an inline-created
 * Wallet/Category resolves them. Confirm (POST /import/confirm) sends the
 * selected rows in file order and, on success, reports the created count
 * and whether any created Transaction made a Cash Wallet negative.
 * The import pipeline's computation endpoints never bump the data version
 * (ADR-0002); confirm is a real write, so its bump refreshes every screen.
 */
data class ImportDraft(
    val phase: ImportPhase = ImportPhase.PICK,
    /** The picked file's display name; null = nothing picked (or the read
     * failed). */
    val fileName: String? = null,
    val fileSizeBytes: Long = 0,
    val preview: ImportPreviewDto? = null,
    /** The row numbers the user kept for the import — every Ready row
     * starts selected; Duplicates and Problems never are. */
    val selected: Set<Int> = emptySet(),
    /** True while a read or a confirm is in flight. */
    val busy: Boolean = false,
    /** The flow-level error (a failed read's or confirm's message); shown
     * on the phase's screen. */
    val error: String? = null,
    val imported: Int = 0,
    /** True when any created Transaction carries the Cash negative-balance
     * warning: the import succeeded but a Cash Wallet went negative. */
    val createdWithWarning: Boolean = false,
    /** The Preview row the Verification editor is open for; null = closed. */
    val editingRow: Int? = null,
    val editorSaving: Boolean = false,
    /** The editor's own error — a failed re-validation call — shown inside
     * the open editor, leaving the draft untouched. */
    val editorError: String? = null,
    /** The inline "New wallet…" create modal stacked on the row editor
     * (ADR-0013/0014, ticket #27); null = closed. The created Wallet is
     * real at once; its name is reported back to the editor's originating
     * field, and the problem rows that waited on it re-validate in a
     * batch. */
    val rowWalletCreate: RowWalletCreateState? = null,
    /** The inline "New category…" create modal stacked on the row editor,
     * the Category mirror; null = closed. */
    val rowCategoryCreate: RowCategoryCreateState? = null,
    /** The row editor's pending auto-select (ticket #27): when the inner
     * Wallet modal saves, it reports the created Wallet's name here so the
     * exact field whose sentinel was picked selects it — and nothing else
     * in the editor moves. Cleared with the editor, so a stale name is
     * never applied to a later editor. */
    val rowWalletToSelect: RowWalletToSelect? = null,
    /** The Category mirror: the created Category's name for the open
     * editor's Category field. */
    val rowCategoryToSelect: String? = null,
)

/**
 * The inline "New wallet…" modal stacked on the row editor (ADR-0013,
 * ticket #27): a create-only WalletModalState draft plus the exact Wallet
 * field whose sentinel was picked — 'wallet' for an Expense/Income row's
 * Wallet field, 'source'/'destination' for a Transfer's From/To — so the
 * created Wallet is auto-selected into it — and the eligibility lock (the
 * Wallet types the originating field may create): an Expense/Income row's
 * Wallet field never creates a Contact Wallet (its select never offers
 * one), a Transfer's From/To allow all four types. The modal is prefilled
 * with the field's missing name from the file.
 */
data class RowWalletCreateState(
    val target: WalletFieldTarget,
    val allowedTypes: Set<WalletType>?,
    val modal: WalletModalState,
)

/**
 * The inline "New category…" modal stacked on the row editor (ADR-0013,
 * ticket #27): a create-only CategoryModalState draft whose type is locked
 * to the row's current type — Expense for an Expense row, Income for an
 * Income row (a Transfer row carries no Category field at all) — so the
 * created Category always fits the row being edited.
 */
data class RowCategoryCreateState(
    val lockedType: CategoryType,
    val modal: CategoryModalState,
)

/** The row editor's pending Wallet auto-select (ticket #27): the name the
 * freshly created Wallet is reported back under, plus the exact field
 * whose sentinel was picked. */
data class RowWalletToSelect(
    val name: String,
    val target: WalletFieldTarget,
)

/**
 * The Import Draft's state machine (ticket #26, #27): drives the flow's
 * phases and keeps the draft consistent — the Verification flips, the
 * batch Revalidation of problem rows (web issues #76/#78), the selection
 * rules, the inline Wallet/Category creation from the row editor
 * (ADR-0013/0014), the discard paths — against the import gateway and the
 * entity gateways inline creation writes through. The computation
 * endpoints never bump the data version (ADR-0002); confirm is a real
 * write, so its bump refreshes every screen in the background.
 */
class ImportViewModel(
    private val imports: ImportGateway,
    private val wallets: WalletGateway,
    private val categories: CategoryGateway,
) : ViewModel() {

    data class UiState(val draft: ImportDraft? = null)

    /**
     * Monotonic reset counter: open, Cancel, "Pick another file", and a
     * successful import all start a fresh draft, and a response from an
     * earlier draft is discarded on arrival — so a Cancel while a read or
     * confirm is in flight can never paint its result onto the next draft.
     */
    private var generation = 0

    /** The picked file's bytes, the one piece the flow state does not
     * carry (they never reach the UI); cleared with the draft. */
    private var pickedContent: ByteArray? = null

    /** True while a batch Revalidation call is in flight, so overlapping
     * triggers (the on-resume re-check and an inline creation's matching
     * re-validation) serialize instead of interleaving their flips. */
    private var revalidating = false

    /** A trigger that arrived while a batch call was in flight: when the
     * in-flight call finishes, one more re-check runs — the full problem
     * re-check, a superset of any trigger that arrived meanwhile. */
    private var revalidationQueued = false

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Start a fresh draft in the pick phase (the Import button). */
    fun open() {
        generation++
        pickedContent = null
        _uiState.value = UiState(ImportDraft())
    }

    /**
     * The file the SAF picker returned, read into memory (web issue #43's
     * pickFile): the draft's pick phase shows its name and size; "Read and
     * validate" uploads the held bytes. A null name or content — the read
     * failed — surfaces the pick phase's error and leaves nothing picked.
     */
    fun onFilePicked(fileName: String?, content: ByteArray?) {
        if (_uiState.value.draft == null) return
        if (fileName == null || content == null) {
            pickedContent = null
            _uiState.update { state ->
                state.draft?.let {
                    state.copy(
                        draft = it.copy(
                            fileName = null,
                            fileSizeBytes = 0,
                            error = "Could not read the file.",
                            busy = false,
                        ),
                    )
                } ?: state
            }
            return
        }
        pickedContent = content
        _uiState.update { state ->
            state.draft?.let {
                state.copy(
                    draft = it.copy(
                        fileName = fileName,
                        fileSizeBytes = content.size.toLong(),
                        error = null,
                        busy = false,
                    ),
                )
            } ?: state
        }
    }

    /**
     * Upload the picked file and show its Preview (POST /import/preview):
     * every row with its verdict — Ready rows auto-selected — and the
     * counts. Nothing is written by this step. A failure (an empty file, a
     * non-.csv/.xlsx upload, a missing template column) surfaces the
     * backend's detail and keeps the pick phase with the file still
     * picked, so the user can retry.
     */
    fun readFile() {
        val draft = _uiState.value.draft ?: return
        if (draft.phase != ImportPhase.PICK || draft.busy) return
        val fileName = draft.fileName ?: return
        val content = pickedContent ?: return
        val gen = generation
        _uiState.update { state ->
            state.draft?.let { state.copy(draft = it.copy(busy = true, error = null)) } ?: state
        }
        viewModelScope.launch {
            try {
                val preview = imports.preview(fileName, content)
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.draft?.let {
                        state.copy(
                            draft = it.copy(
                                phase = ImportPhase.PREVIEW,
                                preview = preview,
                                selected = preview.rows
                                    .filter { row -> row.status == ImportRowStatus.OK }
                                    .mapTo(HashSet()) { row -> row.row },
                                busy = false,
                                error = null,
                                editingRow = null,
                                editorSaving = false,
                                editorError = null,
                                rowWalletCreate = null,
                                rowCategoryCreate = null,
                                rowWalletToSelect = null,
                                rowCategoryToSelect = null,
                            ),
                        )
                    } ?: state
                }
            } catch (cause: Throwable) {
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.draft?.let {
                        state.copy(draft = it.copy(busy = false, error = draftError(cause, "Could not read the file.")))
                    } ?: state
                }
            }
        }
    }

    /** Toggle one ready row's selection; rows that are not Ready are never
     * selectable (the checkbox is disabled for them). */
    fun toggle(rowNumber: Int) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            if (draft.phase != ImportPhase.PREVIEW) return@update state
            val row = draft.preview?.rows?.find { it.row == rowNumber } ?: return@update state
            if (row.status != ImportRowStatus.OK) return@update state
            val next = draft.selected.toMutableSet()
            if (!next.add(rowNumber)) next.remove(rowNumber)
            state.copy(draft = draft.copy(selected = next))
        }
    }

    /** Open the Verification editor for a Preview row — any row, ready,
     * duplicate, or problem (web issue #46). The row's fields stay
     * untouched until Save reports the edited row back. */
    fun openEditor(rowNumber: Int) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            if (draft.phase != ImportPhase.PREVIEW) return@update state
            if (draft.preview?.rows?.none { it.row == rowNumber } != false) return@update state
            state.copy(
                draft = draft.copy(
                    editingRow = rowNumber,
                    editorSaving = false,
                    editorError = null,
                ),
            )
        }
    }

    /** Close the editor, abandoning the edit without changing the row. A
     * pending inline-created auto-select is cleared with it (a stale name
     * must never be applied to an editor opened later). */
    fun closeEditor() {
        _uiState.update { state ->
            state.draft?.let {
                state.copy(
                    draft = it.copy(
                        editingRow = null,
                        editorSaving = false,
                        editorError = null,
                        rowWalletCreate = null,
                        rowCategoryCreate = null,
                        rowWalletToSelect = null,
                        rowCategoryToSelect = null,
                    ),
                )
            } ?: state
        }
    }

    /**
     * Verification's Save (web issues #44/#46): re-validate the edited row
     * — its Wallet/Category names resolved, the CONTEXT.md rules re-run,
     * and the Duplicate check applied with the final key, against the
     * database and the Draft's preceding rows (`earlier_rows`) — and flip
     * its status in place: the row's fields become the edited ones, a row
     * that turns Ready joins the selection, one that stops being Ready
     * leaves it. Nothing is written. A failed call keeps the editor open
     * with its error inline and the draft untouched.
     */
    fun saveRowEdit(input: ImportRowInput) {
        val draft = _uiState.value.draft ?: return
        val rowNumber = draft.editingRow ?: return
        if (draft.phase != ImportPhase.PREVIEW || draft.editorSaving) return
        val preview = draft.preview ?: return
        if (preview.rows.none { it.row == rowNumber }) return
        val gen = generation
        _uiState.update { state ->
            state.draft?.let {
                state.copy(draft = it.copy(editorSaving = true, editorError = null))
            } ?: state
        }
        viewModelScope.launch {
            try {
                val verdict = imports.validateRow(input, earlierRowInputs(preview.rows, rowNumber))
                if (gen != generation) return@launch
                _uiState.update { state ->
                    val current = state.draft ?: return@update state
                    if (current.preview == null || current.editingRow != rowNumber) return@update state
                    val next = current.selected.toMutableSet()
                    if (verdict.status == ImportRowStatus.OK) {
                        next.add(rowNumber)
                    } else {
                        next.remove(rowNumber)
                    }
                    state.copy(
                        draft = current.copy(
                            preview = current.preview.copy(
                                rows = current.preview.rows.map { row ->
                                    if (row.row != rowNumber) {
                                        row
                                    } else {
                                        row.copy(
                                            status = verdict.status,
                                            error = verdict.error,
                                            type = input.type,
                                            date = input.date,
                                            amount = input.amount,
                                            wallet = input.wallet,
                                            source_wallet = input.source_wallet,
                                            destination_wallet = input.destination_wallet,
                                            category = input.category,
                                            description = input.description,
                                            latitude = input.latitude,
                                            longitude = input.longitude,
                                        )
                                    }
                                },
                            ),
                            selected = next,
                            editingRow = null,
                            editorSaving = false,
                            editorError = null,
                            rowWalletCreate = null,
                            rowCategoryCreate = null,
                            rowWalletToSelect = null,
                            rowCategoryToSelect = null,
                        ),
                    )
                }
            } catch (cause: Throwable) {
                if (gen != generation) return@launch
                _uiState.update { state ->
                    val current = state.draft ?: return@update state
                    if (current.editingRow != rowNumber) return@update state
                    state.copy(
                        draft = current.copy(
                            editorSaving = false,
                            editorError = draftError(cause, "Could not validate the row."),
                        ),
                    )
                }
            }
        }
    }

    // --- Batch Revalidation (web issues #76/#78, ticket #27) ---

    /**
     * The on-resume re-check (web issue #76): the Preview resumes — the
     * user returns to the Transactions tab with a live Draft, which the
     * screen observes as this flow re-entering composition (ADR-0002's
     * keep-alive ViewModel never leaves) — and every problem row is
     * re-validated in one batch (POST /import/revalidate-rows) against the
     * Account's current Wallets and Categories: rows that now pass flip to
     * Ready and join the selection, rows still broken keep their narrowed
     * message, and a row that now duplicates is marked so. Ready,
     * Duplicate, and hand-verified rows are untouched; a failed call
     * surfaces as the Draft's error, leaving the rows as they were. A
     * re-check while the flow is not in a live Preview — or with nothing
     * to re-check — does nothing.
     */
    fun recheckProblems() {
        revalidateBatch { true }
    }

    /** The inline-creation trigger (web issue #78): a Wallet created from
     * the row editor re-validates every problem row whose wallet-kind
     * field — its Wallet, or a Transfer's From/To leg — names the created
     * Wallet. The row being edited flips too, even when the editor is then
     * cancelled without saving: its stored values reference the name. */
    private fun revalidateWalletMatches(name: String) {
        revalidateBatch { row -> rowReferencesWallet(row, name) }
    }

    /** The inline-creation trigger's Category mirror (web issue #78): the
     * problem rows whose Category field names the created Category. */
    private fun revalidateCategoryMatches(name: String) {
        revalidateBatch { row -> rowReferencesCategory(row, name) }
    }

    /**
     * One batch Revalidation call and the flips that apply its verdicts
     * (web issue #76): every sendable Draft row travels as the in-file
     * Duplicate context, the problem rows `match` selects are the targets,
     * and each verdict flips its row in place — Ready auto-selected,
     * anything else deselected. Rows without a sendable identity (parse
     * errors) cannot be re-validated and keep their message. No matching
     * problem row means no call at all; a failed call surfaces as the
     * Draft's error, leaving the rows as they were. Overlapping triggers
     * serialize: one call at a time, and a trigger that arrives while a
     * call is in flight queues one more full re-check (a superset of any
     * trigger) to run when it finishes.
     */
    private fun revalidateBatch(match: (ImportRowDto) -> Boolean) {
        val draft = _uiState.value.draft ?: return
        if (draft.phase != ImportPhase.PREVIEW || draft.busy || draft.editorSaving) return
        val preview = draft.preview ?: return
        val rows = mutableListOf<ImportRowInput>()
        val targets = mutableListOf<Int>()
        for (row in preview.rows) {
            val input = rowInput(row) ?: continue
            rows.add(input)
            if (row.status == ImportRowStatus.ERROR && match(row)) {
                targets.add(row.row)
            }
        }
        if (targets.isEmpty()) return
        if (revalidating) {
            revalidationQueued = true
            return
        }
        val gen = generation
        revalidating = true
        viewModelScope.launch {
            try {
                val verdicts = imports.revalidateRows(rows, targets)
                if (gen != generation) return@launch
                applyRevalidationVerdicts(verdicts)
            } catch (cause: Throwable) {
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.draft?.let {
                        state.copy(draft = it.copy(error = draftError(cause, "Could not re-validate the rows.")))
                    } ?: state
                }
            } finally {
                revalidating = false
                if (revalidationQueued) {
                    revalidationQueued = false
                    recheckProblems()
                }
            }
        }
    }

    /**
     * Apply a batch's verdicts to the Draft: each verdict flips its row's
     * status in place — the row's fields untouched, the counts follow —
     * and the selection follows the fresh status (a row that turns Ready
     * joins, one that stops being Ready leaves). A row whose status the
     * editor has since flipped is skipped: the batch computed its verdict
     * from the row's earlier stored values, and the editor's own
     * re-validation is the fresher truth.
     */
    private fun applyRevalidationVerdicts(verdicts: List<ImportRowRevalidationDto>) {
        _uiState.update { state ->
            val current = state.draft ?: return@update state
            if (current.phase != ImportPhase.PREVIEW) return@update state
            val preview = current.preview ?: return@update state
            val verdictByRow = verdicts.associateBy { it.row }
            val next = current.selected.toMutableSet()
            val updated = preview.rows.map { row ->
                val verdict = verdictByRow[row.row] ?: return@map row
                if (row.status != ImportRowStatus.ERROR) return@map row
                if (verdict.status == ImportRowStatus.OK) {
                    next.add(row.row)
                } else {
                    next.remove(row.row)
                }
                row.copy(status = verdict.status, error = verdict.error)
            }
            state.copy(
                draft = current.copy(
                    preview = preview.copy(rows = updated),
                    selected = next,
                ),
            )
        }
    }

    // --- Inline Wallet/Category creation from the row editor
    // (ADR-0013/0014, ticket #27) ---

    /**
     * A "New wallet…" pick from a row-editor select (web issue #77): stack
     * the Wallet create form on the row editor, with the eligibility lock
     * the originating field applies and the field's missing name from the
     * file prefilled. The editor's other fields stay untouched until the
     * create form's save reports the new Wallet back.
     */
    fun onRowWalletAdd(target: WalletFieldTarget, prefillName: String) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            if (draft.phase != ImportPhase.PREVIEW || draft.editingRow == null || draft.editorSaving) {
                return@update state
            }
            if (draft.rowWalletCreate != null || draft.rowCategoryCreate != null) return@update state
            state.copy(
                draft = draft.copy(
                    rowWalletCreate = RowWalletCreateState(
                        target = target,
                        allowedTypes = importEditorWalletCreateAllowedTypes(target),
                        modal = WalletModalState(name = prefillName),
                    ),
                ),
            )
        }
    }

    fun onRowWalletCreateNameChange(value: String) =
        updateRowWalletCreate { it.copy(name = value, error = null) }

    fun onRowWalletCreateTypeChange(value: WalletType) = updateRowWalletCreate {
        if (value == WalletType.CONTACT) {
            // A Contact wallet starts at €0: drop any drafted amount.
            it.copy(type = value, openingBalance = "", error = null)
        } else {
            it.copy(type = value, error = null)
        }
    }

    fun onRowWalletCreateOpeningBalanceChange(value: String) =
        updateRowWalletCreate { it.copy(openingBalance = value, error = null) }

    /** Cancel only the inline form: the row editor's draft stays as it was. */
    fun cancelRowWalletCreate() {
        _uiState.update { state ->
            state.draft?.let { state.copy(draft = it.copy(rowWalletCreate = null)) } ?: state
        }
    }

    /**
     * Confirm the inline "New wallet…" form: the Wallet is created for real
     * through the same endpoint the Wallets screen uses (ADR-0014),
     * auto-selected into the exact field whose sentinel was picked, and the
     * problem rows waiting on its name re-validate in one batch — the
     * row being edited included, even when its editor is then cancelled
     * without saving.
     */
    fun submitRowWalletCreate() {
        val create = _uiState.value.draft?.rowWalletCreate ?: return
        val modal = create.modal
        if (!modal.canSubmit) return
        val openingBalance = if (modal.type == WalletType.CONTACT) {
            "0.00"
        } else {
            normalizeOpeningBalance(modal.openingBalance)
        }
        if (openingBalance == null) {
            updateRowWalletCreate { it.copy(error = "Enter an amount of €0 or more.") }
            return
        }
        val target = create.target
        val gen = generation
        viewModelScope.launch {
            updateRowWalletCreate { it.copy(submitting = true, error = null) }
            try {
                val created = wallets.createWallet(modal.name.trim(), modal.type, openingBalance)
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.draft?.let { draft ->
                        state.copy(
                            draft = draft.copy(
                                rowWalletCreate = null,
                                rowWalletToSelect = RowWalletToSelect(name = created.name, target = target),
                            ),
                        )
                    } ?: state
                }
                revalidateWalletMatches(created.name)
            } catch (error: ApiException) {
                if (gen != generation) return@launch
                updateRowWalletCreate {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A wallet with this name already exists.",
                            "Could not create the wallet.",
                        ),
                    )
                }
            } catch (_: Exception) {
                if (gen != generation) return@launch
                updateRowWalletCreate { it.copy(submitting = false, error = "Could not create the wallet.") }
            }
        }
    }

    /**
     * A "New category…" pick from the row editor's Category select (an
     * Expense or Income row — a Transfer never carries one): stack the
     * create form on the row editor, its type locked to the row's current
     * type and the field's missing name from the file prefilled.
     */
    fun onRowCategoryAdd(lockedType: CategoryType, prefillName: String) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            if (draft.phase != ImportPhase.PREVIEW || draft.editingRow == null || draft.editorSaving) {
                return@update state
            }
            if (draft.rowCategoryCreate != null || draft.rowWalletCreate != null) return@update state
            state.copy(
                draft = draft.copy(
                    rowCategoryCreate = RowCategoryCreateState(
                        lockedType = lockedType,
                        modal = CategoryModalState(type = lockedType, name = prefillName),
                    ),
                ),
            )
        }
    }

    fun onRowCategoryCreateNameChange(value: String) =
        updateRowCategoryCreate { it.copy(name = value, error = null) }

    fun onRowCategoryCreateIconChange(value: String) =
        updateRowCategoryCreate { it.copy(icon = value, error = null) }

    fun onRowCategoryCreateColorChange(value: String) =
        updateRowCategoryCreate { it.copy(color = value, error = null) }

    /** Cancel only the inline form: the row editor's draft stays as it was. */
    fun cancelRowCategoryCreate() {
        _uiState.update { state ->
            state.draft?.let { state.copy(draft = it.copy(rowCategoryCreate = null)) } ?: state
        }
    }

    /**
     * Confirm the inline "New category…" form: the Category is created for
     * real through the same endpoint the Categories screen uses
     * (ADR-0014), auto-selected into the row editor's Category field, and
     * the problem rows waiting on its name re-validate in one batch.
     */
    fun submitRowCategoryCreate() {
        val create = _uiState.value.draft?.rowCategoryCreate ?: return
        val modal = create.modal
        if (!modal.canSubmit) return
        val lockedType = create.lockedType
        val gen = generation
        viewModelScope.launch {
            updateRowCategoryCreate { it.copy(submitting = true, error = null) }
            try {
                val created = categories.createCategory(
                    modal.name.trim(),
                    lockedType,
                    modal.icon,
                    modal.color,
                )
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.draft?.let { draft ->
                        state.copy(
                            draft = draft.copy(
                                rowCategoryCreate = null,
                                rowCategoryToSelect = created.name,
                            ),
                        )
                    } ?: state
                }
                revalidateCategoryMatches(created.name)
            } catch (error: ApiException) {
                if (gen != generation) return@launch
                updateRowCategoryCreate {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A category with this name already exists.",
                            "Could not create the category.",
                        ),
                    )
                }
            } catch (_: Exception) {
                if (gen != generation) return@launch
                updateRowCategoryCreate { it.copy(submitting = false, error = "Could not create the category.") }
            }
        }
    }

    private fun updateRowWalletCreate(transform: (WalletModalState) -> WalletModalState) {
        _uiState.update { state ->
            state.draft?.rowWalletCreate?.let { create ->
                state.copy(draft = state.draft.copy(rowWalletCreate = create.copy(modal = transform(create.modal))))
            } ?: state
        }
    }

    private fun updateRowCategoryCreate(transform: (CategoryModalState) -> CategoryModalState) {
        _uiState.update { state ->
            state.draft?.rowCategoryCreate?.let { create ->
                state.copy(draft = state.draft.copy(rowCategoryCreate = create.copy(modal = transform(create.modal))))
            } ?: state
        }
    }

    /**
     * Confirm the import (POST /import/confirm): the selected rows in file
     * order, transactionally — the backend re-validates every row, and any
     * invalid or now-duplicate row rejects the whole batch (its detail
     * surfaces as the Preview's error, nothing imported). On success the
     * draft reports the created count and whether any created Transaction
     * made a Cash Wallet negative, and its write bump refreshes every
     * screen in the background (ADR-0002).
     */
    fun confirm() {
        val draft = _uiState.value.draft ?: return
        if (draft.phase != ImportPhase.PREVIEW || draft.busy) return
        val preview = draft.preview ?: return
        val rows = preview.rows
            .filter { it.row in draft.selected }
            .mapNotNull(::rowInput)
        val gen = generation
        _uiState.update { state ->
            state.draft?.let {
                state.copy(draft = it.copy(busy = true, error = null))
            } ?: state
        }
        viewModelScope.launch {
            try {
                val created: List<TransactionDto> = imports.confirm(rows)
                if (gen != generation) return@launch
                _uiState.update { state ->
                    val current = state.draft ?: return@update state
                    state.copy(
                        draft = current.copy(
                            phase = ImportPhase.DONE,
                            busy = false,
                            imported = created.size,
                            createdWithWarning = created.any { it.warning },
                            error = null,
                            editingRow = null,
                            editorSaving = false,
                            editorError = null,
                            rowWalletCreate = null,
                            rowCategoryCreate = null,
                            rowWalletToSelect = null,
                            rowCategoryToSelect = null,
                        ),
                    )
                }
            } catch (cause: Throwable) {
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.draft?.let {
                        state.copy(draft = it.copy(busy = false, error = draftError(cause, "Could not confirm the import.")))
                    } ?: state
                }
            }
        }
    }

    /**
     * "Pick another file": back to the pick phase with the file cleared —
     * the second discard path of the Draft (the rows, their edits, and the
     * selections are dropped with it).
     */
    fun pickAgain() {
        val draft = _uiState.value.draft ?: return
        if (draft.phase != ImportPhase.PREVIEW) return
        generation++
        pickedContent = null
        _uiState.update { state ->
            state.draft?.let {
                state.copy(
                    draft = it.copy(
                        phase = ImportPhase.PICK,
                        fileName = null,
                        fileSizeBytes = 0,
                        preview = null,
                        selected = emptySet(),
                        busy = false,
                        error = null,
                        imported = 0,
                        createdWithWarning = false,
                        editingRow = null,
                        editorSaving = false,
                        editorError = null,
                        rowWalletCreate = null,
                        rowCategoryCreate = null,
                        rowWalletToSelect = null,
                        rowCategoryToSelect = null,
                    ),
                )
            } ?: state
        }
    }

    /** Discard the draft (the Cancel button). */
    fun cancel() = discard()

    /** Discard the draft after a successful import (the Back button). */
    fun done() = discard()

    private fun discard() {
        generation++
        pickedContent = null
        _uiState.value = UiState()
    }

    /** The web transport's readDetail mapping for the import endpoints:
     * the backend's detail verbatim when it had one, else the fallback. */
    private fun draftError(cause: Throwable, fallback: String): String =
        (cause as? ApiException)?.detail ?: fallback
}

/** A draft row as an ImportRowInput for the wire, or null when it has no
 * sendable identity (no type, date, or amount — a parse-error row
 * contributes no key anywhere). A blank description travels as null (a
 * blank description matches a missing one, ADR-0006). */
internal fun rowInput(row: ImportRowDto): ImportRowInput? {
    val type = row.type ?: return null
    if (type != TransactionType.EXPENSE &&
        type != TransactionType.INCOME &&
        type != TransactionType.TRANSFER
    ) {
        return null
    }
    val date = row.date ?: return null
    val amount = row.amount ?: return null
    return ImportRowInput(
        row = row.row,
        type = type,
        amount = amount,
        date = date,
        wallet = row.wallet,
        source_wallet = row.source_wallet,
        destination_wallet = row.destination_wallet,
        category = row.category,
        description = row.description?.trim().orEmpty().ifEmpty { null },
        latitude = row.latitude,
        longitude = row.longitude,
    )
}

/** The draft's rows that precede the given row in the file, with their
 * edits applied, as wire inputs — the in-file half of the Duplicate check,
 * which the re-validation endpoint cannot see by itself. Rows without a
 * sendable identity contribute nothing, exactly as in the Preview. */
internal fun earlierRowInputs(rows: List<ImportRowDto>, beforeRow: Int): List<ImportRowInput> =
    rows.filter { it.row < beforeRow }.mapNotNull(::rowInput)

/** The fresh-Preview counts of the given rows — what the sticky confirm bar
 * shows after a row flips (the response's snapshot counts are only the
 * first render's). */
data class PreviewCounts(
    val ready: Int,
    val duplicates: Int,
    val problems: Int,
)

/** The fresh-Preview counts of the given rows — what the sticky confirm bar
 * shows after a row flips (the response's snapshot counts are only the
 * first render's). */
internal fun previewCounts(preview: ImportPreviewDto): PreviewCounts {
    var ready = 0
    var duplicates = 0
    var problems = 0
    for (row in preview.rows) {
        when (row.status) {
            ImportRowStatus.OK -> ready++
            ImportRowStatus.DUPLICATE -> duplicates++
            ImportRowStatus.ERROR -> problems++
        }
    }
    return PreviewCounts(ready, duplicates, problems)
}
