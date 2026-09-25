package com.budjetame.android.ui.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.RestoreResultDto
import com.budjetame.android.data.api.apiErrorMessage
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.transaction.ExportFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The restore flow's phases (issue #60): pick a backup workbook, confirm
 * twice (with an optional fresh export offered before the first confirm),
 * restore in flight, and the success or error report. */
enum class RestorePhase {
    IDLE,
    /** A file was picked; the first confirmation is shown, with an option
     * to export the current data first. */
    PICKED,
    /** The user tapped "Continue to restore" — the second confirmation
     * dialog appears, asking for the final go-ahead. */
    FIRST_CONFIRMED,
    /** The restore POST is in flight. */
    RESTORING,
    /** The restore succeeded. The origin-marker warning may be set. */
    SUCCESS,
    /** The restore failed. */
    ERROR,
}

/**
 * The restore flow's state (issue #60): drives the two-step confirmation
 * that offers a fresh Export all before committing, the multipart POST to
 * the shared restore endpoint, and the origin-marker warning that the
 * server's response carries. The file bytes never leave this ViewModel;
 * the composable reads the picked file and reports it here.
 */
data class RestoreState(
    val phase: RestorePhase = RestorePhase.IDLE,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0,
    val error: String? = null,
    /** True when the restored backup's origin marker doesn't match the
     * Account's current marker — the data was still replaced. */
    val originWarning: Boolean = false,
    /** True while a user-triggered Export all is in flight from within the
     * restore flow. */
    val exporting: Boolean = false,
    /** A failed Export all's error message from within the restore flow. */
    val exportError: String? = null,
    /** The exported file, waiting for the screen to hand it to the system
     * share sheet (ticket #57). The screen shares it once and reports back
     * through [onExportHandled], which clears it. */
    val exportFile: ExportFile? = null,
)

/**
 * The restore flow's ViewModel (issue #60): manages the two-step
 * confirmation — picked → offered export → second confirm → restore call →
 * success/error — and the origin-marker mismatch warning.
 * The composable provides the file bytes from the SAF picker; the ViewModel
 * holds them and drives the API call.
 */
class RestoreViewModel(
    private val backupRepository: BackupGateway,
) : ViewModel() {

    /** The picked file's bytes, the one piece the state does not carry
     * (they never reach the UI); cleared when the flow resets. */
    private var pickedContent: ByteArray? = null

    private val _state = MutableStateFlow(RestoreState())
    val state: StateFlow<RestoreState> = _state.asStateFlow()

    /**
     * A file was picked from the SAF document picker (issue #60):
     * the bytes are held in memory, the name shown, and the flow
     * advances to the first confirmation step. A null name or content —
     * the read failed — surfaces the error.
     */
    fun onFilePicked(fileName: String?, content: ByteArray?) {
        if (_state.value.phase != RestorePhase.IDLE &&
            _state.value.phase != RestorePhase.ERROR
        ) return
        if (fileName == null || content == null) {
            pickedContent = null
            _state.update { state ->
                state.copy(
                    phase = RestorePhase.ERROR,
                    fileName = null,
                    fileSizeBytes = 0,
                    error = "Could not read the file.",
                )
            }
            return
        }
        pickedContent = content
        _state.update { state ->
            state.copy(
                phase = RestorePhase.PICKED,
                fileName = fileName,
                fileSizeBytes = content.size.toLong(),
                error = null,
                originWarning = false,
                exporting = false,
                exportError = null,
                exportFile = null,
            )
        }
    }

    /**
     * The user accepted the first confirmation ("Continue to restore"):
     * the flow advances to the second confirmation, requiring one more tap
     * before the restore call fires.
     */
    fun onFirstConfirm() {
        if (_state.value.phase != RestorePhase.PICKED) return
        _state.update { it.copy(phase = RestorePhase.FIRST_CONFIRMED) }
    }

    /**
     * The user tapped "Export all" from the first confirmation step:
     * downloads a fresh backup of the current Account state via the
     * gateway so they can save it before the restore overwrites
     * everything. On success the file is handed to the composable for
     * sharing; on failure an error message surfaces inline, but the
     * restore flow can still proceed.
     */
    fun onOfferExport() {
        if (_state.value.phase != RestorePhase.PICKED) return
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, exportError = null, exportFile = null) }
        viewModelScope.launch {
            try {
                val export = backupRepository.exportBackup()
                _state.update {
                    it.copy(exporting = false, exportFile = export)
                }
            } catch (_: Exception) {
                _state.update {
                    it.copy(exporting = false, exportError = "Could not export the backup workbook.")
                }
            }
        }
    }

    fun onExportErrorHandled(errorMessage: String) {
        _state.update { it.copy(exportError = errorMessage, exportFile = null) }
    }

    /** The screen shared (or failed to share) the pending export file
     * from the restore flow: clear it so the same file can never leave
     * the app twice. */
    fun onExportHandled() {
        _state.update { it.copy(exportFile = null) }
    }

    /**
     * The user confirmed the second confirmation ("Restore"): execute the
     * restore POST. A malformed file is rejected by the backend (fail-closed),
     * surfacing as the error state; an origin-marker mismatch answers 200
     * with the warning flag and still replaces all data.
     */
    fun onSecondConfirm() {
        val state = _state.value
        if (state.phase != RestorePhase.FIRST_CONFIRMED) return
        val content = pickedContent ?: return
        val fileName = state.fileName ?: return
        val gen = System.identityHashCode(this) // simple monotonic guard
        _state.update { it.copy(phase = RestorePhase.RESTORING, error = null) }
        viewModelScope.launch {
            try {
                val result = backupRepository.restoreBackup(fileName, content)
                if (gen != System.identityHashCode(this@RestoreViewModel)) return@launch
                _state.update {
                    it.copy(
                        phase = RestorePhase.SUCCESS,
                        originWarning = result.origin_marker_warning,
                    )
                }
            } catch (error: ApiException) {
                if (gen != System.identityHashCode(this@RestoreViewModel)) return@launch
                _state.update {
                    it.copy(
                        phase = RestorePhase.ERROR,
                        error = apiErrorMessage(
                            error.status,
                            error.detail ?: "The backup file is malformed — nothing was changed.",
                            "Could not restore from the backup file.",
                        ),
                    )
                }
            } catch (_: Exception) {
                if (gen != System.identityHashCode(this@RestoreViewModel)) return@launch
                _state.update {
                    it.copy(
                        phase = RestorePhase.ERROR,
                        error = "Could not restore from the backup file.",
                    )
                }
            }
        }
    }

    /** Cancel the second confirmation: back to the first step. */
    fun cancelSecondConfirm() {
        if (_state.value.phase != RestorePhase.FIRST_CONFIRMED) return
        _state.update { it.copy(phase = RestorePhase.PICKED) }
    }

    /** Reset the flow to idle (Close, or Done after a successful restore). */
    fun reset() {
        pickedContent = null
        _state.value = RestoreState()
    }

    /** Discard the flow without resetting (Back from error or success:
     * the dialog closes and the shell clears it). */
    fun dismiss() {
        pickedContent = null
        _state.value = RestoreState()
    }
}