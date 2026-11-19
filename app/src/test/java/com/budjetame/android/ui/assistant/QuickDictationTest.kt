package com.budjetame.android.ui.assistant

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AssistantApi
import com.budjetame.android.data.api.AssistantSettingsApi
import com.budjetame.android.data.assistant.ApiAssistantRepository
import com.budjetame.android.data.assistant.ApiAssistantSettingsRepository
import com.budjetame.android.data.dictation.DictationRecognizer
import com.budjetame.android.data.dictation.DictationTones
import com.budjetame.android.ui.common.english
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Quick Dictation (ticket #65; CONTEXT.md: Quick Dictation): the Assistant
 * in quick mode, opened from outside the app as a voice-only card. Tested at
 * the same two seams as AssistantDictationTest — the HTTP API (a
 * MockWebServer) and the device's recognizer and tones (fakes) — with the
 * auto-close driven on the test Main dispatcher's virtual clock.
 */
class QuickDictationTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AssistantViewModel

    private val turnTexts = ConcurrentLinkedQueue<String>()

    /** The fake server's answer to the Assistant Settings status. */
    @Volatile
    private var settingsResponse: () -> MockResponse = {
        jsonResponse("""{"configured":true,"provider":"anthropic","model":"claude-haiku","last4":"abcd"}""")
    }

    /** The fake server's answer to a turn, from the sentence sent. */
    @Volatile
    private var turnResponse: (String) -> MockResponse = { text -> reply("Spesa 30,00 € oggi: $text") }

    private val events = mutableListOf<String>()

    private val json = Json { ignoreUnknownKeys = true }

    private inner class FakeRecognizer : DictationRecognizer {
        var listener: DictationRecognizer.Listener? = null

        override fun start(language: String, listener: DictationRecognizer.Listener) {
            this.listener = listener
            events += "start $language"
        }

        override fun stop() {
            events += "stop"
        }

        override fun cancel() {
            events += "cancel"
        }
    }

    private val recognizer = FakeRecognizer()

    private val tones = object : DictationTones {
        override fun playStart() {
            events += "start tone"
        }

        override fun playEnd() {
            events += "end tone"
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Installed before any ViewModel exists: its init fetches the status.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val body = request.body.readUtf8()
                return when (path) {
                    "/api/assistant/settings" -> settingsResponse()
                    "/api/assistant/turns" -> {
                        val text = json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
                        turnTexts.add(text)
                        turnResponse(text)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun jsonResponse(body: String, status: Int = 200) = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun reply(text: String) = jsonResponse("""{"reply":${JsonPrimitive(text)},"transcript_tail":[]}""")

    private fun offline() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

    private fun createQuickViewModel() {
        val client = ApiClient(server.url("/api/").toString()) { null }
        viewModel = AssistantViewModel(
            turns = ApiAssistantRepository(client.create(AssistantApi::class.java)),
            settings = ApiAssistantSettingsRepository(client.create(AssistantSettingsApi::class.java)),
            recognizer = recognizer,
            tones = tones,
            locale = MutableStateFlow("it"),
            quick = true,
        )
    }

    private fun awaitState(predicate: (AssistantViewModel.UiState) -> Boolean): AssistantViewModel.UiState =
        runBlocking { withTimeout(5_000) { viewModel.uiState.first(predicate) } }

    private val listener get() = recognizer.listener!!

    /** One Dictation from start to what was heard. */
    private fun dictate(heard: String) {
        awaitState { it.available }
        viewModel.startDictation()
        listener.onReadyForSpeech()
        listener.onResult(heard)
    }

    private fun advanceClock(millis: Long) {
        mainRule.dispatcher.scheduler.advanceTimeBy(millis)
        mainRule.dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `a reply that needs nothing more closes the card about 3 s later`() {
        createQuickViewModel()
        assertTrue(viewModel.uiState.value.open)

        dictate("trenta euro all'Esselunga con la carta")

        val state = awaitState { it.closing }
        assertEquals("Spesa 30,00 € oggi: trenta euro all'Esselunga con la carta", state.lines.last().text?.english())
        advanceClock(2_900)
        assertTrue(viewModel.uiState.value.open)
        advanceClock(200)
        assertFalse(viewModel.uiState.value.open)
    }

    @Test
    fun `touching the card cancels the auto-close`() {
        createQuickViewModel()
        dictate("trenta euro all'Esselunga con la carta")
        awaitState { it.closing }

        viewModel.holdOpen()

        assertFalse(viewModel.uiState.value.closing)
        advanceClock(10_000)
        assertTrue(viewModel.uiState.value.open)
    }

    @Test
    fun `a reply asking the Assistant's one question keeps the card open and listens for the answer`() {
        turnResponse = { text ->
            if (text.startsWith("trenta")) reply("Spesa 30,00 € oggi: Esselunga. Quale carta: Visa o Amex?") else reply("Carta: Visa.")
        }
        createQuickViewModel()
        dictate("trenta euro all'Esselunga con la carta")

        val asking = awaitState { it.lines.size == 2 && it.listening }
        assertFalse(asking.closing)
        advanceClock(10_000)
        assertTrue(viewModel.uiState.value.open)

        listener.onReadyForSpeech()
        listener.onResult("la Visa")
        awaitState { it.closing }
        assertEquals(listOf("trenta euro all'Esselunga con la carta", "la Visa"), turnTexts.toList())
        advanceClock(AssistantViewModel.AUTO_CLOSE_MILLIS)
        assertFalse(viewModel.uiState.value.open)
    }

    @Test
    fun `without Assistant Settings the card cannot run and never listens`() {
        settingsResponse = { jsonResponse("""{"configured":false}""") }
        createQuickViewModel()
        // Asked before the status is read: it waits for the Assistant to answer.
        viewModel.startDictation()

        val state = awaitState { it.cannotRun != null }
        assertEquals(AssistantViewModel.CannotRun.NO_ASSISTANT_SETTINGS, state.cannotRun)
        assertFalse(state.listening)
        assertTrue(state.open)
        viewModel.startDictation()
        assertTrue(turnTexts.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `offline the card cannot run`() {
        settingsResponse = { offline() }
        createQuickViewModel()

        assertEquals(AssistantViewModel.CannotRun.OFFLINE, awaitState { it.cannotRun != null }.cannotRun)
    }

    @Test
    fun `a session the server no longer accepts is signed out`() {
        settingsResponse = { jsonResponse("""{"detail":"Not authenticated"}""", status = 401) }
        createQuickViewModel()

        assertEquals(AssistantViewModel.CannotRun.SIGNED_OUT, awaitState { it.cannotRun != null }.cannotRun)
    }

    @Test
    fun `a turn answered 409 says the Assistant Settings are gone, and the card stays open on it`() {
        turnResponse = { jsonResponse("""{"detail":"No Assistant Settings"}""", status = 409) }
        createQuickViewModel()
        awaitState { it.available }

        dictate("trenta euro all'Esselunga con la carta")

        val state = awaitState { it.cannotRun != null }
        assertEquals(AssistantViewModel.CannotRun.NO_ASSISTANT_SETTINGS, state.cannotRun)
        assertTrue(state.open)
        assertFalse(state.pending)
    }

    @Test
    fun `a turn that cannot reach the server says offline and keeps nothing for later`() {
        turnResponse = { offline() }
        createQuickViewModel()
        awaitState { it.available }

        dictate("trenta euro all'Esselunga con la carta")

        val state = awaitState { it.cannotRun != null }
        assertEquals(AssistantViewModel.CannotRun.OFFLINE, state.cannotRun)
        assertFalse(state.pending)
        assertEquals("", state.text)
        advanceClock(10_000)
        assertTrue(viewModel.uiState.value.open)
    }
}
