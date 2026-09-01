package com.budjetame.android.data.api

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

/** A tiny service to drive the transport's write-bump rule (ADR-0002). */
interface TestApi {
    @GET("read")
    suspend fun read(): String

    @POST("write")
    suspend fun write(): String

    @POST("import/preview")
    suspend fun preview(): String
}

class DataVersionTest {

    private lateinit var server: MockWebServer
    private lateinit var api: TestApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = ApiClient(server.url("/api/").toString()) { null }
        api = client.create(TestApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a successful write bumps the version`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("\"ok\""))
        val before = DataVersion.current()
        api.write()
        assertEquals(before + 1, DataVersion.current())
    }

    @Test
    fun `a failed write never bumps`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"boom"}"""))
        val before = DataVersion.current()
        try {
            api.write()
        } catch (_: Exception) {
            // expected — the transport must not bump on failure
        }
        assertEquals(before, DataVersion.current())
    }

    @Test
    fun `reads never bump`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("\"ok\""))
        val before = DataVersion.current()
        api.read()
        assertEquals(before, DataVersion.current())
    }

    @Test
    fun `import computation endpoints are exempt from the bump`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("\"ok\""))
        val before = DataVersion.current()
        api.preview()
        assertEquals(before, DataVersion.current())
    }

    @Test
    fun `the token interceptor sends the bearer header`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("\"ok\""))
        val client = ApiClient(server.url("/api/").toString()) { "secret-token" }
        client.create(TestApi::class.java).read()
        val request = server.takeRequest()
        assertEquals("Bearer secret-token", request.getHeader("Authorization"))
    }
}
