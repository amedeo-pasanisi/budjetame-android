package com.budjetame.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Export all (ticket #57, web #112): download the Account's Backup and hold
 * it until the user saves it through the document picker or shares it.
 *
 * The ViewModel lives in the Activity's store, like the Assistant Settings
 * block's, so every Settings section asking [backupViewModel] for it gets the
 * same instance: the Backup section shows it, and Restore's "offer a fresh
 * Backup first" step calls the same [exportAll] (CONTEXT.md: Restore). The
 * dialog calls [close] when it goes, which drops the held workbook.
 */
class BackupViewModel(private val backups: BackupGateway) : ViewModel() {

    data class UiState(
        /** True while the Backup downloads. */
        val exporting: Boolean = false,
        /** The downloaded Backup, ready to save or share; null before Export
         * all, after a failed download, and once the dialog closed. */
        val backup: BackupFile? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var exportJob: Job? = null

    /** Download a fresh Backup, replacing any held one. A failure shows an
     * error and holds no file, so nothing can be saved from it. */
    fun exportAll() {
        if (_uiState.value.exporting) return
        _uiState.value = UiState(exporting = true)
        exportJob = viewModelScope.launch {
            try {
                val backup = backups.exportAll()
                _uiState.value = UiState(backup = backup)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = UiState(error = "Could not export the backup.")
            }
        }
    }

    /** The device could not write or share the held Backup: show [message].
     * The Backup stays held, so the user can try again or the other way. */
    fun onDeliveryFailed(message: String) {
        _uiState.update { it.copy(error = message) }
    }

    /** Drop the held Backup and stop a download in flight. */
    fun close() {
        exportJob?.cancel()
        _uiState.value = UiState()
    }
}

/** The Activity-scoped [BackupViewModel] every Settings section shares. */
@Composable
fun backupViewModel(backups: BackupGateway): BackupViewModel = viewModel { BackupViewModel(backups) }
