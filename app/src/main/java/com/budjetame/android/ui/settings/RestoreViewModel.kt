package com.budjetame.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.backup.BackupGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Restore from backup (ticket #60, web #115, web ADR-0030): replace all the
 * Account's data with a picked Backup workbook's contents.
 *
 * Picking a file opens a two-step confirmation: step one ([RestoreStep.Confirm])
 * explains what is replaced and offers a fresh Export all first; step two
 * ([RestoreStep.FinalConfirm]) commits. Only that second confirm uploads, so
 * cancelling at either step sends nothing. The server validates the whole
 * file before replacing anything, so a refused file (422) changed nothing.
 *
 * A successful restore calls [onRestored] — the shell clears its undo stack,
 * whose entries are stale by definition — and the transport's write bump
 * (ADR-0002) revalidates every screen. The origin-mismatch `warning` shows
 * after the restore, as a dismissible notice: there is no pre-check.
 *
 * Activity-scoped like [BackupViewModel], so the restore survives the Settings
 * dialog going: [close] resets the block, but a restore already sent still
 * lands and still calls [onRestored].
 */
class RestoreViewModel(
    private val backups: BackupGateway,
    private val onRestored: () -> Unit,
) : ViewModel() {

    /** The restore flow's step, mirroring the web's RestoreStep. */
    sealed interface RestoreStep {
        /** The Backup block's "Restore from backup…" entry. */
        data object Idle : RestoreStep

        /** Step one: what is replaced, and the offer to Export all first. */
        data class Confirm(val fileName: String) : RestoreStep

        /** Step two: the commit. */
        data class FinalConfirm(val fileName: String) : RestoreStep

        data object Restoring : RestoreStep

        /** The restore landed, but the Backup came from another Account. */
        data class Warning(val message: String) : RestoreStep

        data object Done : RestoreStep

        /** The restore failed and nothing changed: the server's message,
         * or a fallback when it sent none. */
        data class Error(val message: String) : RestoreStep
    }

    data class UiState(val step: RestoreStep = RestoreStep.Idle)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** The picked workbook, held from the pick until the restore is sent or
     * the flow is cancelled. */
    private var picked: Pair<String, ByteArray>? = null

    /** Bumped whenever the flow resets, so a restore that lands after its
     * dialog went never repaints a newer flow. */
    private var generation = 0

    private fun setStep(step: RestoreStep) {
        _uiState.value = UiState(step)
    }

    /** The system picker answered. A null name or content means the device
     * could not read the document: an error, and nothing to upload. */
    fun onFilePicked(fileName: String?, content: ByteArray?) {
        if (_uiState.value.step == RestoreStep.Restoring) return
        if (fileName == null || content == null) {
            picked = null
            setStep(RestoreStep.Error(UNREADABLE_FILE))
            return
        }
        picked = fileName to content
        setStep(RestoreStep.Confirm(fileName))
    }

    /** Step one's go-ahead: on to the commit step. */
    fun continueToFinalConfirm() {
        val step = _uiState.value.step
        if (step is RestoreStep.Confirm) setStep(RestoreStep.FinalConfirm(step.fileName))
    }

    /** Step two's commit: upload the picked workbook. Ignored at any other
     * step, so no single tap can skip a confirmation. */
    fun confirmRestore() {
        if (_uiState.value.step !is RestoreStep.FinalConfirm) return
        val (fileName, content) = picked ?: return
        picked = null
        setStep(RestoreStep.Restoring)
        val run = generation
        viewModelScope.launch {
            val outcome = try {
                val result = backups.restore(fileName, content)
                onRestored()
                result.warning?.let { RestoreStep.Warning(it) } ?: RestoreStep.Done
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RestoreStep.Error((error as? ApiException)?.detail ?: RESTORE_FAILED)
            }
            if (run == generation) setStep(outcome)
        }
    }

    /** The warning notice is dismissed: the restore is done. */
    fun dismissWarning() {
        if (_uiState.value.step is RestoreStep.Warning) setStep(RestoreStep.Done)
    }

    /** Cancel at either confirmation, or "Try again" after an error: back to
     * idle, the picked file dropped. Nothing is sent. */
    fun cancel() {
        if (_uiState.value.step == RestoreStep.Restoring) return
        reset()
    }

    /** The Settings dialog went: back to idle. A restore already sent is
     * not cancelled — the server may have committed it — so it still lands
     * and still clears the undo stack. */
    fun close() {
        reset()
    }

    private fun reset() {
        generation++
        picked = null
        setStep(RestoreStep.Idle)
    }

    private companion object {
        /** The web's restore fallback when the server sent no detail. */
        const val RESTORE_FAILED = "Could not restore backup"
        const val UNREADABLE_FILE = "Could not read the file."
    }
}

/** The Activity-scoped [RestoreViewModel]: [onRestored] is the shell's
 * undo-stack clear. */
@Composable
fun restoreViewModel(backups: BackupGateway, onRestored: () -> Unit): RestoreViewModel =
    viewModel { RestoreViewModel(backups, onRestored) }
