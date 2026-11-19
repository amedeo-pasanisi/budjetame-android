package com.budjetame.android.ui.settings

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.backup.BackupFile
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.backup.RestoreResult
import com.budjetame.android.ui.settings.RestoreViewModel.RestoreStep
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Restore from backup (ticket #60, web #115) at the ViewModel seam: a fake
 * BackupGateway whose restore is held open by the test, so every step of
 * picked → confirm 1 → confirm 2 → restoring → done / warning / error is
 * observable, and a counter standing in for the shell's undo-stack clear.
 */
class RestoreViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    /** Each restore() records what it was sent and waits on the next
     * deferred the test completes. */
    private class FakeBackups : BackupGateway {
        var pending = CompletableDeferred<RestoreResult>()
        val restored = mutableListOf<Pair<String, ByteArray>>()

        override suspend fun download(): BackupFile = error("Restore never downloads")

        override suspend fun restore(filename: String, content: ByteArray): RestoreResult {
            restored += filename to content
            return pending.await()
        }
    }

    private val backups = FakeBackups()
    private var undoClears = 0
    private val viewModel = RestoreViewModel(backups, onRestored = { undoClears++ })

    private val workbook = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private fun step() = viewModel.uiState.value.step

    private suspend fun awaitStep(predicate: (RestoreStep) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first { predicate(it.step) } }
    }

    /** Pick the workbook and pass both confirmations. */
    private fun pickAndConfirmBoth() {
        viewModel.onFilePicked("budjetame-backup-2026-11-01.xlsx", workbook)
        viewModel.continueToFinalConfirm()
        viewModel.confirmRestore()
    }

    @Test
    fun `picking a file opens step one, then step two, and only the second confirm uploads`() = runBlocking {
        assertEquals(RestoreStep.Idle, step())

        viewModel.onFilePicked("budjetame-backup-2026-11-01.xlsx", workbook)
        assertEquals(RestoreStep.Confirm("budjetame-backup-2026-11-01.xlsx"), step())

        viewModel.continueToFinalConfirm()
        assertEquals(RestoreStep.FinalConfirm("budjetame-backup-2026-11-01.xlsx"), step())
        assertEquals(0, backups.restored.size)

        viewModel.confirmRestore()
        assertEquals(RestoreStep.Restoring, step())
        assertEquals(1, backups.restored.size)
        assertEquals("budjetame-backup-2026-11-01.xlsx", backups.restored.single().first)
        assertArrayEquals(workbook, backups.restored.single().second)
    }

    @Test
    fun `cancelling at step one sends nothing`() {
        viewModel.onFilePicked("backup.xlsx", workbook)

        viewModel.cancel()

        assertEquals(RestoreStep.Idle, step())
        viewModel.confirmRestore()
        assertEquals(0, backups.restored.size)
    }

    @Test
    fun `cancelling at step two sends nothing`() {
        viewModel.onFilePicked("backup.xlsx", workbook)
        viewModel.continueToFinalConfirm()

        viewModel.cancel()

        assertEquals(RestoreStep.Idle, step())
        viewModel.confirmRestore()
        assertEquals(0, backups.restored.size)
    }

    @Test
    fun `step one's confirm alone never uploads`() {
        viewModel.onFilePicked("backup.xlsx", workbook)

        viewModel.confirmRestore()

        assertEquals(RestoreStep.Confirm("backup.xlsx"), step())
        assertEquals(0, backups.restored.size)
    }

    @Test
    fun `a clean restore ends done and clears the undo stack`() = runBlocking {
        pickAndConfirmBoth()
        assertEquals(0, undoClears)

        backups.pending.complete(RestoreResult(warning = null))
        awaitStep { it != RestoreStep.Restoring }

        assertEquals(RestoreStep.Done, step())
        assertEquals(1, undoClears)
    }

    @Test
    fun `an origin-mismatch warning shows after the restore, clears the undo stack, and dismisses to done`() = runBlocking {
        pickAndConfirmBoth()

        backups.pending.complete(RestoreResult(warning = "Backup was exported from a@x.com, not from b@x.com"))
        awaitStep { it != RestoreStep.Restoring }

        assertEquals(RestoreStep.Warning("Backup was exported from a@x.com, not from b@x.com"), step())
        assertEquals(1, undoClears)

        viewModel.dismissWarning()
        assertEquals(RestoreStep.Done, step())
    }

    @Test
    fun `a 422 shows the server's message and leaves the undo stack alone`() = runBlocking {
        pickAndConfirmBoth()

        backups.pending.completeExceptionally(
            ApiException(
                status = 422,
                detail = "Could not parse workbook: not a zip file",
                message = "Could not parse workbook: not a zip file",
            ),
        )
        awaitStep { it != RestoreStep.Restoring }

        assertEquals(RestoreStep.Error("Could not parse workbook: not a zip file"), step())
        assertEquals(0, undoClears)
    }

    @Test
    fun `a failure without a server detail shows the web's fallback, and Try again returns to idle`() = runBlocking {
        pickAndConfirmBoth()

        backups.pending.completeExceptionally(IOException("offline"))
        awaitStep { it != RestoreStep.Restoring }

        assertEquals(RestoreStep.Error("Could not restore backup"), step())

        viewModel.cancel()
        assertEquals(RestoreStep.Idle, step())
    }

    @Test
    fun `a picked file the device could not read shows an error and uploads nothing`() {
        viewModel.onFilePicked(null, null)

        assertEquals(RestoreStep.Error("Could not read the file."), step())
        assertEquals(0, backups.restored.size)
    }

    @Test
    fun `closing the dialog mid-restore lets the restore land and still clears the undo stack`() = runBlocking {
        pickAndConfirmBoth()

        viewModel.close()
        assertEquals(RestoreStep.Idle, step())

        backups.pending.complete(RestoreResult(warning = null))
        withTimeout(5_000) { while (undoClears == 0) yield() }

        assertEquals(1, undoClears)
        // The dialog is gone: the next Settings opens on the idle block.
        assertEquals(RestoreStep.Idle, step())
    }
}
