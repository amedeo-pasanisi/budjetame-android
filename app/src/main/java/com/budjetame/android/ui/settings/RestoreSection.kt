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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budjetame.android.R
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.ui.common.localized
import com.budjetame.android.ui.common.resolve
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
            text = stringResource(R.string.settings_restore),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.settings_restore_description),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(
            onClick = { filePicker.launch(RESTORE_MIME_TYPES) },
            enabled = state.step == RestoreStep.Idle,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(stringResource(R.string.settings_restore_choose_file))
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
                title = localized { Text(stringResource(R.string.settings_restore_title)) },
                text = localized {
                    Column {
                        Text(stringResource(R.string.settings_restore_confirm_text))
                        Text(
                            text = stringResource(R.string.settings_restore_confirm_lost),
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
                            Text(
                                stringResource(
                                    if (backupState.downloading) R.string.settings_exporting else R.string.settings_restore_export_first,
                                ),
                            )
                        }
                        BackupDelivery(backup)
                    }
                },
                confirmButton = localized {
                    TextButton(onClick = viewModel::continueToFinalConfirm) {
                        Text(stringResource(R.string.settings_restore_continue))
                    }
                },
                dismissButton = localized {
                    TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }

        is RestoreStep.FinalConfirm -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = localized { Text(stringResource(R.string.settings_restore_title)) },
            text = localized { Text(stringResource(R.string.settings_restore_final_text, step.filename)) },
            confirmButton = localized {
                TextButton(onClick = viewModel::confirmRestore) {
                    Text(stringResource(R.string.settings_restore_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = localized {
                TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.common_cancel)) }
            },
        )

        // The upload is under way: nothing to tap, and no dismissing it.
        RestoreStep.Restoring -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = localized { Text(stringResource(R.string.settings_restore_restoring)) },
            text = localized { Text(stringResource(R.string.settings_restore_restoring_text)) },
            confirmButton = {},
        )

        is RestoreStep.Warning -> AlertDialog(
            onDismissRequest = viewModel::dismissWarning,
            title = localized { Text(stringResource(R.string.settings_restore_warning_title)) },
            text = localized {
                Column {
                    Text(step.message.resolve())
                    Text(
                        text = stringResource(R.string.settings_restore_warning_text),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = localized {
                TextButton(onClick = viewModel::dismissWarning) {
                    Text(stringResource(R.string.settings_restore_continue))
                }
            },
        )

        RestoreStep.Done -> AlertDialog(
            onDismissRequest = onCloseSettings,
            title = localized { Text(stringResource(R.string.settings_restore_done_title)) },
            text = localized { Text(stringResource(R.string.settings_restore_done_text)) },
            confirmButton = localized {
                TextButton(onClick = onCloseSettings) { Text(stringResource(R.string.settings_restore_done)) }
            },
        )

        is RestoreStep.Error -> AlertDialog(
            onDismissRequest = viewModel::cancel,
            title = localized { Text(stringResource(R.string.settings_restore_error_title)) },
            text = localized {
                Column {
                    Text(step.message.resolve(), color = MaterialTheme.colorScheme.error)
                    Text(
                        text = stringResource(R.string.settings_restore_error_text),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = localized {
                TextButton(onClick = viewModel::cancel) { Text(stringResource(R.string.settings_restore_try_again)) }
            },
        )
    }
}
