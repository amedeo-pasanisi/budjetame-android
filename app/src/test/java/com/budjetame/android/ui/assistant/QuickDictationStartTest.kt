package com.budjetame.android.ui.assistant

import com.budjetame.android.InMemoryTokenStorage
import com.budjetame.android.data.Session
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.auth.ApiAuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Quick Dictation's first step (ticket #65): before it listens, the card
 * reads the signed-in Account (the Locale a Dictation listens in) through
 * the same session the app uses. Tested at the HTTP seam.
 */
class QuickDictationStartTest {

    private lateinit var server: MockWebServer
    private val storage = InMemoryTokenStorage()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun start(): QuickDictationStart = runBlocking {
        val session = Session(storage)
        val client = ApiClient(server.url("/api/").toString()) { session.token }
        quickDictationStart(ApiAuthRepository(client.create(AuthApi::class.java), session))
    }

    @Test
    fun `a signed-in Account is ready to listen in its Locale`() {
        storage.save("token")
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"id":1,"email":"a@b.c","language":"it"}"""),
        )

        assertEquals(QuickDictationStart.Ready(locale = "it"), start())
    }

    @Test
    fun `without a session the card cannot run, signed out`() {
        assertEquals(QuickDictationStart.CannotRun(AssistantViewModel.CannotRun.SIGNED_OUT), start())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a session the server refuses is signed out`() {
        storage.save("token")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Not authenticated"}"""))

        assertEquals(QuickDictationStart.CannotRun(AssistantViewModel.CannotRun.SIGNED_OUT), start())
    }

    @Test
    fun `an unreachable server is offline`() {
        storage.save("token")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertEquals(QuickDictationStart.CannotRun(AssistantViewModel.CannotRun.OFFLINE), start())
    }
}
