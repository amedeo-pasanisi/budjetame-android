package com.budjetame.android.data.backup

import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.BackupApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The Backup download at the HTTP seam (ticket #57, web #112): the real
 * repository, Retrofit and OkHttp against a MockWebServer standing in for
 * `GET /backup/export`, which answers the workbook's raw bytes and the dated
 * `budjetame-backup-YYYY-MM-DD.xlsx` name in Content-Disposition.
 */
class BackupRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: BackupGateway

    /** Not a real workbook: the client never reads the file, it passes the
     * server's bytes through untouched. */
    private val workbook = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x7F, 0x10)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = ApiClient(server.url("/api/").toString()) { "secret-token" }
        repository = ApiBackupRepository(client.create(BackupApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun workbookResponse(disposition: String?): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .apply { if (disposition != null) setHeader("Content-Disposition", disposition) }
            .setBody(Buffer().write(workbook))

    @Test
    fun `the Backup is fetched with the bearer token and comes back as the server's bytes and filename`() = runBlocking {
        server.enqueue(workbookResponse("attachment; filename=\"budjetame-backup-2026-11-19.xlsx\""))

        val backup = repository.download()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/backup/export", request.requestUrl?.encodedPath)
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
        assertEquals("budjetame-backup-2026-11-19.xlsx", backup.filename)
        assertArrayEquals(workbook, backup.content)
    }

    @Test
    fun `a stripped Content-Disposition falls back to the web's backup filename`() = runBlocking {
        server.enqueue(workbookResponse(disposition = null))

        assertEquals("budjetame-backup.xlsx", repository.download().filename)
    }

    @Test
    fun `a success with an empty body is no Backup`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))

        try {
            repository.download()
            fail("expected an empty answer to throw")
        } catch (_: IllegalStateException) {
            // Nothing to save: the ViewModel shows its error line.
        }
    }

    @Test
    fun `a failed download throws the server's status instead of returning a file`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"boom"}"""),
        )

        try {
            repository.download()
            fail("expected the failed download to throw")
        } catch (error: ApiException) {
            assertEquals(500, error.status)
        }
    }
}
