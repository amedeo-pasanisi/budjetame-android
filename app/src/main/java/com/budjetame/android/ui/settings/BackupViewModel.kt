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

/** How handing a held Backup to the device can fail, with the line the
 * Backup section shows. */
enum class BackupDeliveryFailure(val message: String) {
    SAVE("Could not save the backup file."),
    SHARE("Could not share the backup file."),
}

/**
 * The Backup's "Export all" (ticket #57, web #112): download the Account's
 * Backup and hold it until the user saves it through the document picker or
 * shares it.
 *
 * The ViewModel lives in the Activity's store, like the Assistant Settings
 * block's, so every Settings section asking [backupViewModel] for it gets the
 * same instance: the Backup section shows it, and Restore's step that offers
 * a fresh Backup first can call the same [downloadBackup] and render
 * [BackupDelivery]. The dialog calls [close] when it goes, which drops the
 * held workbook.
 */
class BackupViewModel(private val backups: BackupGateway) : ViewModel() {

    data class UiState(
        /** True while the Backup downloads. */
        val downloading: Boolean = false,
        /** The downloaded Backup, ready to save or share; null before Export
         * all, after a failed download, and once the dialog closed. */
        val backup: BackupFile? = null,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    /** Download a fresh Backup, replacing any held one. A failure shows an
     * error and holds no file, so nothing can be saved from it. */
    fun downloadBackup() {
        if (_uiState.value.downloading) return
        _uiState.value = UiState(downloading = true)
        downloadJob = viewModelScope.launch {
            try {
                val backup = backups.download()
                _uiState.value = UiState(backup = backup)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.value = UiState(error = "Could not export the backup.")
            }
        }
    }

    /** A save or share of the held Backup starts: an earlier one's failure
     * line goes. */
    fun onDeliveryStarted() {
        _uiState.update { it.copy(error = null) }
    }

    /** The device could not write or share the held Backup. The Backup stays
     * held, so the user can try again or the other way. */
    fun onDeliveryFailed(failure: BackupDeliveryFailure) {
        _uiState.update { it.copy(error = failure.message) }
    }

    /** Drop the held Backup and stop a download in flight. */
    fun close() {
        downloadJob?.cancel()
        _uiState.value = UiState()
    }
}

/** The Activity-scoped [BackupViewModel] every Settings section shares. */
@Composable
fun backupViewModel(backups: BackupGateway): BackupViewModel = viewModel { BackupViewModel(backups) }
