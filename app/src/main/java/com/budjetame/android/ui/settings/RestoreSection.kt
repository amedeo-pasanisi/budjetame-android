package com.budjetame.android.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.ui.imports.readPickedFile
import com.budjetame.android.ui.settings.RestoreViewModel.RestoreStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The picker's accepted types: the Backup's .xlsx, plus the generic
 * octet-stream some providers label it with (the server refuses anything
 * that is not a Backup workbook with a 422 and changes nothing). */
private val RESTORE_MIME_TYPES = arrayOf(
    BACKUP_MIME_TYPE,
    "application/octet-stream",
)

/**
 * "Restore from backup…" in the Settings dialog's Backup block (ticket #60,
 * web #115), next to Export all: pick a Backup workbook, pass the two-step
 * confirmation, and the server replaces all the Account's data with it. The
 * steps open over the Settings dialog, with the web's English copy.
 *
 * [onRestored] is the shell's undo-stack clear; [onCloseSettings] closes the
 * Settings dialog from the "Restore complete" notice.
 */
@Composable
fun RestoreSection(
    backups: BackupGateway,
    onRestored: () -> Unit,
    onCloseSettings: () -> Unit,
) {
    val viewModel = restoreViewModel(backups, onRestored)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Composed while the dialog shows: the flow resets when the dialog goes.
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    // The Import screen's picker pattern: the document is read into memory
    // here, so the ViewModel stays free of Android framework types.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // A cancelled picker answers null: nothing picked, nothing to say.
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readPickedFile(context.contentResolver, uri) }
            viewModel.onFilePicked(picked?.first, picked?.second)
        }
    }

    Column(modifier = Modifier.padding(top = 16.dp)) {
        Text(
            text = "Restore from backup…",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Replace all your data with the contents of a previously exported backup workbook.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(
            onClick = { filePicker.launch(RESTORE_MIME_TYPES) },
            enabled = state.step == RestoreStep.Idle,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Choose file…")
        }
    }

    RestoreStepDialog(
        step = state.step,
        backups = backups,
        viewModel = viewModel,
        onCloseSettings = onCloseSettings,
    )
}

/** The restore flow's current step, as a dialog over Settings; nothing at
 * [RestoreStep.Idle]. */
@Composable
private fun RestoreStepDialog(
    step: RestoreStep,
    backups: BackupGateway,
    viewModel: RestoreViewModel,
    onCloseSettings: () -> Unit,
) {
    when (step) {
        RestoreStep.Idle -> Unit

        is RestoreStep.Confirm -> {
            // Step one offers a fresh Export all of the current state, through
            // the Backup block's own shared ViewModel and save/share row.
            val backup = backupViewModel(backups)
            val backupState by backup.uiState.collectAsStateWithLifecycle()
            AlertDialog(
                onDismissRequest = viewModel::cancel,
                title = { Text("Restore from backup") },
                text = {
                    Column {
                        Text(
                            "This will replace all your current Transactions, Wallets, Categories, and " +
                                "Recurring definitions with the backup file's contents. This cannot be undone.",
                        )
                        Text(
                            text = "Everything entered after this backup was made will be lost.",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = step.filename,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        OutlinedButton(
                            onClick = backup::downloadBackup,
                            enabled = !backupState.downloading,
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(if (backupState.downloading) "Exporting…" else "Export current state first")
                        }
                        BackupDelivery(backup)
                    }
                },
                confirmButton = {
                    TextButton(onClick = viewModel::continueToFinalConfirm) { Text("Continue") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                },
            )
        }

        is RestoreStep.FinalConfirm -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text("Restore from backup") },
            text = {
                Text(
                    "All your current data will be replaced with the contents of ${step.filename}. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text("Restore from backup", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancel) { Text("Cancel") }
            },
        )

        // The upload is under way: nothing to tap, and no dismissing it.
        RestoreStep.Restoring -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Restoring…") },
            text = { Text("Replacing your data from the backup file.") },
            confirmButton = {},
        )

        is RestoreStep.Warning -> AlertDialog(
            onDismissRequest = viewModel::dismissWarning,
            title = { Text("Origin mismatch") },
            text = {
                Column {
                    Text(step.message)
                    Text(
                        text = "The backup was exported from a different Account. Restoring will replace all " +
                            "your data with the backup's contents. This is a legitimate way to migrate data " +
                            "between Accounts.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWarning) { Text("Continue") }
            },
        )

        RestoreStep.Done -> AlertDialog(
            onDismissRequest = onCloseSettings,
            title = { Text("Restore complete") },
            text = { Text("Your Account data has been replaced with the backup file's contents.") },
            confirmButton = {
                TextButton(onClick = onCloseSettings) { Text("Done") }
            },
        )

        is RestoreStep.Error -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = { Text("Restore failed") },
            text = {
                Column {
                    Text(step.message, color = MaterialTheme.colorScheme.error)
                    Text(
                        text = "The backup file could not be restored. No data was changed.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::cancel) { Text("Try again") }
            },
        )
    }
}
