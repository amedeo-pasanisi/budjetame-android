package com.budjetame.android.ui.settings

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway
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

    /** Each exportAll() waits on the next deferred the test completes. */
    private class FakeBackups : BackupGateway {
        var pending = CompletableDeferred<BackupFile>()
        var calls = 0

        override suspend fun exportAll(): BackupFile {
            calls++
            return pending.await()
        }
    }

    private val backups = FakeBackups()
    private val viewModel = BackupViewModel(backups)

    private val workbook = BackupFile("budjetame-backup-2026-11-19.xlsx", byteArrayOf(0x50, 0x4B, 0x03, 0x04))

    private suspend fun awaitState(predicate: (BackupViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    @Test
    fun `Export all is busy while downloading, then holds the Backup under the server's filename`() = runBlocking {
        viewModel.exportAll()
        assertTrue(viewModel.uiState.value.exporting)
        assertNull(viewModel.uiState.value.backup)

        backups.pending.complete(workbook)
        awaitState { !it.exporting }

        val backup = viewModel.uiState.value.backup ?: error("expected a Backup")
        assertEquals("budjetame-backup-2026-11-19.xlsx", backup.filename)
        assertArrayEquals(workbook.content, backup.content)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a second tap while downloading starts no second download`() = runBlocking {
        viewModel.exportAll()
        viewModel.exportAll()

        assertEquals(1, backups.calls)
    }

    @Test
    fun `a failed download shows the error and holds no file, and a retry can succeed`() = runBlocking {
        viewModel.exportAll()
        backups.pending.completeExceptionally(ApiException(status = 500, detail = "boom", message = "boom"))
        awaitState { !it.exporting }

        assertEquals("Could not export the backup.", viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.backup)

        backups.pending = CompletableDeferred()
        viewModel.exportAll()
        assertNull(viewModel.uiState.value.error)
        backups.pending.complete(workbook)
        awaitState { it.backup != null }
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a Backup the device could not save or share shows that error and stays held`() = runBlocking {
        viewModel.exportAll()
        backups.pending.complete(workbook)
        awaitState { it.backup != null }

        viewModel.onDeliveryFailed("Could not save the backup file.")

        assertEquals("Could not save the backup file.", viewModel.uiState.value.error)
        assertEquals("budjetame-backup-2026-11-19.xlsx", viewModel.uiState.value.backup?.filename)
    }

    @Test
    fun `closing the dialog drops the Backup and any download in flight`() = runBlocking {
        viewModel.exportAll()
        viewModel.close()

        backups.pending.complete(workbook)

        val state = viewModel.uiState.value
        assertFalse(state.exporting)
        assertNull(state.backup)
        assertNull(state.error)
    }
}
