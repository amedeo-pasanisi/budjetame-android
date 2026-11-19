package com.budjetame.android.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The .xlsx content type: the Backup's MIME, for the picker and the share sheet. */
internal const val BACKUP_MIME_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * The Backup block of the Settings dialog (ticket #57, web #112): Export all
 * downloads the Account's complete Backup, then [BackupDelivery] offers to
 * save it or share it. Distinct from the ledger's Export, which carries only
 * the filtered Transactions.
 */
@Composable
fun BackupSection(backups: BackupGateway) {
    val viewModel = backupViewModel(backups)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The block is composed while the dialog shows: drop the held workbook
    // when the dialog goes.
    DisposableEffect(viewModel) {
        onDispose { viewModel.close() }
    }

    Column {
        Text(
            text = "Export all",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = "Downloads your complete Account data as a multi-sheet backup workbook.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        TextButton(
            onClick = viewModel::downloadBackup,
            enabled = !state.downloading,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(if (state.downloading) "Exporting…" else "Export all")
        }
        BackupDelivery(viewModel)
    }
}

/**
 * The held Backup's way out of the app, and the Backup's error line: save it
 * through the system document picker under the server's dated filename, or
 * hand it to the share sheet. Shows nothing but the error until a Backup is
 * held. Public so Restore's "offer a fresh Backup first" step can render it.
 */
@Composable
fun BackupDelivery(viewModel: BackupViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
    ) { uri ->
        val backup = viewModel.uiState.value.backup
        // A cancelled picker answers null: nothing is written, nothing to say.
        if (uri == null || backup == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = withContext(Dispatchers.IO) { writeBackup(context, uri, backup) }
            if (!written) viewModel.onDeliveryFailed(BackupDeliveryFailure.SAVE)
        }
    }

    Column {
        state.backup?.let { backup ->
            Text(
                text = "Backup ready: ${backup.filename}",
                style = MaterialTheme.typography.labelSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        viewModel.onDeliveryStarted()
                        saveLauncher.launch(backup.filename)
                    },
                ) { Text("Save to device") }
                TextButton(
                    onClick = {
                        viewModel.onDeliveryStarted()
                        scope.launch {
                            if (!shareBackup(context, backup)) viewModel.onDeliveryFailed(BackupDeliveryFailure.SHARE)
                        }
                    },
                ) { Text("Share") }
            }
        }

        state.error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Write the Backup's bytes to the document the picker created. */
private fun writeBackup(context: Context, uri: Uri, backup: BackupFile): Boolean = try {
    context.contentResolver.openOutputStream(uri)?.use { it.write(backup.content) } != null
} catch (_: Exception) {
    false
}

/** Write the Backup to the app's cache under the server's filename and open
 * the share sheet on its FileProvider Uri (the ledger Export's provider
 * path). False when the file could not be written or no app can take it. */
private suspend fun shareBackup(context: Context, backup: BackupFile): Boolean {
    val uri = withContext(Dispatchers.IO) {
        try {
            val directory = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(directory, backup.filename).apply { writeBytes(backup.content) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
    } ?: return false
    val send = Intent(Intent.ACTION_SEND).apply {
        type = BACKUP_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(send, null))
        true
    } catch (_: Exception) {
        false
    }
}
