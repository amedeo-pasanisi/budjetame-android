package com.budjetame.android.ui.imports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.imports.ImportGateway
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
 * when it stops being one — and Confirm (POST /import/confirm) sends the
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
)

/**
 * The Import Draft's state machine (ticket #26): drives the flow's phases
 * and keeps the draft consistent — the Verification flips, the selection
 * rules, the discard paths — against the import gateway. The computation
 * endpoints never bump the data version (ADR-0002); confirm is a real
 * write, so its bump refreshes every screen in the background.
 */
class ImportViewModel(private val imports: ImportGateway) : ViewModel() {

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

    /** Close the editor, abandoning the edit without changing the row. */
    fun closeEditor() {
        _uiState.update { state ->
            state.draft?.let {
                state.copy(draft = it.copy(editingRow = null, editorSaving = false, editorError = null))
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
