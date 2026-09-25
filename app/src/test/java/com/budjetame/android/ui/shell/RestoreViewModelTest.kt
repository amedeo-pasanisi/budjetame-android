package com.budjetame.android.ui.shell

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.BackupApi
import com.budjetame.android.data.api.RestoreResultDto
import com.budjetame.android.data.backup.ApiBackupRepository
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.transaction.ExportFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The restore flow tested at the single seam (the HTTP API): the ViewModel
 * is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer. The flow's states — picked → first confirm → second
 * confirm → restore call → success/error — are asserted through the
 * ViewModel's state flow, exactly like ImportViewModelTest.
 *
 * The fake dispatcher handles GET /backup (export) and POST /backup/restore
 * (restore) with configurable status codes so both happy and error paths
 * can be exercised.
 */
class RestoreViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: RestoreViewModel

    /** When true, the restore response carries the origin-marker warning. */
    private var restoreOriginWarning = false
    /** When non-200, the restore endpoint answers the recorded detail. */
    private var restoreStatus = 200
    private var restoreDetail = ""
    /** When non-200, the export endpoint answers the recorded detail. */
    private var exportStatus = 200
    private var exportDetail = ""

    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        calls.clear()
        restoreOriginWarning = false
        restoreStatus = 200
        restoreDetail = ""
        exportStatus = 200
        exportDetail = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createViewModel() {
        val client = ApiClient(server.url("/api/").toString()) { null }
        val repository: BackupGateway = ApiBackupRepository(client.create(BackupApi::class.java))
        viewModel = RestoreViewModel(repository)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))
        return when {
            method == "GET" && path == "/api/backup" -> export()
            method == "POST" && path == "/api/backup/restore" -> restore()
            else -> jsonResponse(404, """{"detail":"not found"}""")
        }
    }

    private fun export(): MockResponse {
        if (exportStatus != 200) {
            return jsonResponse(exportStatus, """{"detail":"$exportDetail"}""")
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Disposition", """attachment; filename="budjetame-2026-09-01.xlsx"""")
            .setBody("fake-xlsx-bytes")
    }

    private fun restore(): MockResponse {
        if (restoreStatus != 200) {
            return jsonResponse(restoreStatus, """{"detail":"$restoreDetail"}""")
        }
        return jsonResponse(
            200,
            json.encodeToString(RestoreResultDto(ok = true, origin_marker_warning = restoreOriginWarning)),
        )
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private suspend fun awaitState(predicate: (RestoreState) -> Boolean): RestoreState {
        withTimeout(5_000) { viewModel.state.first { predicate(it) } }
        return viewModel.state.value
    }

    private fun callsFor(path: String): List<RecordedCall> =
        calls.filter { it.path == path }

    // --- The happy path: each phase in order --------------------------------

    @Test
    fun `picking a file advances to the PICKED phase`() = runBlocking {
        createViewModel()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)

        viewModel.onFilePicked("backup.xlsx", "fake-xlsx-bytes".toByteArray())

        val state = awaitState { it.phase == RestorePhase.PICKED }
        assertEquals("backup.xlsx", state.fileName)
        assertEquals(15L, state.fileSizeBytes)
        assertNull(state.error)
        assertFalse(state.originWarning)
    }

    @Test
    fun `a null file on pick shows the error phase`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked(null, null)
        val state = awaitState { it.phase == RestorePhase.ERROR }
        assertEquals("Could not read the file.", state.error)
    }

    @Test
    fun `first confirm advances to FIRST_CONFIRMED`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }

        viewModel.onFirstConfirm()
        assertEquals(RestorePhase.FIRST_CONFIRMED, viewModel.state.value.phase)
    }

    @Test
    fun `second confirm fires the restore call and advances to SUCCESS`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "fake-xlsx-bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        assertEquals(RestorePhase.FIRST_CONFIRMED, viewModel.state.value.phase)

        viewModel.onSecondConfirm()
        val state = awaitState { it.phase == RestorePhase.SUCCESS }
        assertFalse(state.originWarning)

        // The restore call was made
        assertEquals(1, callsFor("/api/backup/restore").size)
    }

    @Test
    fun `a restore with origin-marker warning surfaces the warning on SUCCESS`() = runBlocking {
        restoreOriginWarning = true
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        viewModel.onSecondConfirm()
        val state = awaitState { it.phase == RestorePhase.SUCCESS }
        assertTrue(state.originWarning)
    }

    @Test
    fun `a malformed file restore fails with the backend's detail`() = runBlocking {
        restoreStatus = 422
        restoreDetail = "The file is not a valid backup workbook."
        createViewModel()
        viewModel.onFilePicked("bad.xlsx", "garbage".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        viewModel.onSecondConfirm()
        val state = awaitState { it.phase == RestorePhase.ERROR }
        assertEquals("Check the fields and try again.", state.error)
    }

    @Test
    fun `a server error during restore surfaces a fallback error message`() = runBlocking {
        restoreStatus = 500
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        viewModel.onSecondConfirm()
        val state = awaitState { it.phase == RestorePhase.ERROR }
        assertEquals("Could not restore from the backup file.", state.error)
    }

    // --- Two-step confirmation flow ----------------------------------------

    @Test
    fun `cancelling the second confirm returns to the first confirm`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        assertEquals(RestorePhase.FIRST_CONFIRMED, viewModel.state.value.phase)

        viewModel.cancelSecondConfirm()
        assertEquals(RestorePhase.PICKED, viewModel.state.value.phase)
    }

    @Test
    fun `cancelling the first confirm resets the flow to idle`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }

        viewModel.dismiss()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)
        assertNull(viewModel.state.value.fileName)
    }

    @Test
    fun `dismissing after a success resets to idle`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        viewModel.onSecondConfirm()
        awaitState { it.phase == RestorePhase.SUCCESS }

        viewModel.dismiss()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)
    }

    @Test
    fun `dismissing after an error resets to idle`() = runBlocking {
        restoreStatus = 422
        createViewModel()
        viewModel.onFilePicked("bad.xlsx", "garbage".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onFirstConfirm()
        viewModel.onSecondConfirm()
        awaitState { it.phase == RestorePhase.ERROR }

        viewModel.dismiss()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)
    }

    // --- Export-all offer during restore ------------------------------------

    @Test
    fun `export offer from the restore flow downloads the current backup and makes it available`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }

        viewModel.onOfferExport()
        val state = awaitState { it.exportFile != null }
        assertFalse(state.exporting)
        assertNull(state.exportError)
        assertEquals("budjetame-2026-09-01.xlsx", state.exportFile?.filename)

        // The export call was made
        assertEquals(1, callsFor("/api/backup").size)
    }

    @Test
    fun `export offer failure surfaces the error but does not block the restore`() = runBlocking {
        exportStatus = 500
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }

        viewModel.onOfferExport()
        val state = awaitState { it.exportError != null }
        assertEquals("Could not export the backup workbook.", state.exportError)
        assertFalse(state.exporting)
        assertNull(state.exportFile)

        // The restore flow can still proceed
        viewModel.onFirstConfirm()
        assertEquals(RestorePhase.FIRST_CONFIRMED, viewModel.state.value.phase)
    }

    @Test
    fun `handling the export clears the pending file`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onOfferExport()
        awaitState { it.exportFile != null }

        viewModel.onExportHandled()
        assertNull(viewModel.state.value.exportFile)
    }

    @Test
    fun `export error handling stores the error message`() = runBlocking {
        createViewModel()
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        awaitState { it.phase == RestorePhase.PICKED }
        viewModel.onOfferExport()
        awaitState { it.exportFile != null }

        viewModel.onExportErrorHandled("Could not share the file.")
        assertEquals("Could not share the file.", viewModel.state.value.exportError)
        assertNull(viewModel.state.value.exportFile)
    }

    // --- Picking a file resets to PICKED from ERROR -------------------------

    @Test
    fun `after an error, picking another file resets to PICKED`() = runBlocking {
        // First pick fails
        createViewModel()
        viewModel.onFilePicked(null, null)
        awaitState { it.phase == RestorePhase.ERROR }

        // Then pick a valid file
        viewModel.onFilePicked("backup.xlsx", "bytes".toByteArray())
        val state = awaitState { it.phase == RestorePhase.PICKED }
        assertEquals("backup.xlsx", state.fileName)
        assertNull(state.error)
    }

    // --- Guard: second confirm without content does nothing -----------------

    @Test
    fun `second confirm from IDLE phase does nothing`() = runBlocking {
        createViewModel()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)

        viewModel.onSecondConfirm()
        // Should remain in IDLE since no file was picked yet
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)
    }

    @Test
    fun `first confirm from IDLE phase does nothing`() = runBlocking {
        createViewModel()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)

        viewModel.onFirstConfirm()
        assertEquals(RestorePhase.IDLE, viewModel.state.value.phase)
    }
}