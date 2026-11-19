package com.budjetame.android.ui.settings

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.backup.RestoreResult
import com.budjetame.android.ui.common.english
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Backup section's Export all (ticket #57) at the ViewModel seam: a fake
 * BackupGateway whose download is held open by the test, so the busy state
 * is observable before the Backup (or the failure) arrives.
 */
class BackupViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    /** Each download() waits on the next deferred the test completes. */
    private class FakeBackups : BackupGateway {
        var pending = CompletableDeferred<BackupFile>()
        var calls = 0

        override suspend fun download(): BackupFile {
            calls++
            return pending.await()
        }

        override suspend fun restore(filename: String, content: ByteArray): RestoreResult =
            error("Export all never restores")
    }

    private val backups = FakeBackups()
    private val viewModel = BackupViewModel(backups)

    private val workbook = BackupFile("budjetame-backup-2026-11-19.xlsx", byteArrayOf(0x50, 0x4B, 0x03, 0x04))

    private suspend fun awaitState(predicate: (BackupViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    @Test
    fun `Export all is busy while downloading, then holds the Backup under the server's filename`() = runBlocking {
        viewModel.downloadBackup()
        assertTrue(viewModel.uiState.value.downloading)
        assertNull(viewModel.uiState.value.backup)

        backups.pending.complete(workbook)
        awaitState { !it.downloading }

        val backup = viewModel.uiState.value.backup ?: error("expected a Backup")
        assertEquals("budjetame-backup-2026-11-19.xlsx", backup.filename)
        assertArrayEquals(workbook.content, backup.content)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a second tap while downloading starts no second download`() = runBlocking {
        viewModel.downloadBackup()
        viewModel.downloadBackup()

        assertEquals(1, backups.calls)
    }

    @Test
    fun `a failed download shows the error and holds no file, and a retry can succeed`() = runBlocking {
        viewModel.downloadBackup()
        backups.pending.completeExceptionally(ApiException(status = 500, detail = "boom", message = "boom"))
        awaitState { !it.downloading }

        assertEquals("Could not export the backup.", viewModel.uiState.value.error?.english())
        assertNull(viewModel.uiState.value.backup)

        backups.pending = CompletableDeferred()
        viewModel.downloadBackup()
        assertNull(viewModel.uiState.value.error)
        backups.pending.complete(workbook)
        awaitState { it.backup != null }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a Backup the device could not save or share shows that error and stays held`() = runBlocking {
        viewModel.downloadBackup()
        backups.pending.complete(workbook)
        awaitState { it.backup != null }

        viewModel.onDeliveryFailed(BackupDeliveryFailure.SAVE)

        assertEquals("Could not save the backup file.", viewModel.uiState.value.error?.english())
        assertEquals("budjetame-backup-2026-11-19.xlsx", viewModel.uiState.value.backup?.filename)
    }

    @Test
    fun `a new save or share clears an earlier one's failure line`() = runBlocking {
        viewModel.downloadBackup()
        backups.pending.complete(workbook)
        awaitState { it.backup != null }
        viewModel.onDeliveryFailed(BackupDeliveryFailure.SHARE)

        viewModel.onDeliveryStarted()

        assertNull(viewModel.uiState.value.error)
        assertEquals("budjetame-backup-2026-11-19.xlsx", viewModel.uiState.value.backup?.filename)
    }

    @Test
    fun `closing the dialog drops the Backup and any download in flight`() = runBlocking {
        viewModel.downloadBackup()
        viewModel.close()

        backups.pending.complete(workbook)

        val state = viewModel.uiState.value
        assertFalse(state.downloading)
        assertNull(state.backup)
        assertNull(state.error)
    }
}
