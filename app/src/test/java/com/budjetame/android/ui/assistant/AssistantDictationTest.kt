package com.budjetame.android.ui.assistant

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AssistantApi
import com.budjetame.android.data.api.AssistantSettingsApi
import com.budjetame.android.data.assistant.ApiAssistantRepository
import com.budjetame.android.data.assistant.ApiAssistantSettingsRepository
import com.budjetame.android.data.dictation.DictationRecognizer
import com.budjetame.android.data.dictation.DictationTones
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Dictation in the Assistant (ticket #69; CONTEXT.md: Dictation), tested at
 * two seams: the HTTP API (a MockWebServer, as in AssistantViewModelTest)
 * and the device's speech recognizer and tones (fakes the test drives as
 * the platform would: ready for speech, then a result, nothing heard, or a
 * failure).
 */
class AssistantDictationTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AssistantViewModel

    /** The app's shared Locale the ViewModel reads. */
    private lateinit var localeFlow: MutableStateFlow<String>

    private val turnTexts = ConcurrentLinkedQueue<String>()

    /** When set, a turn's answer waits for it: the reply is pending. */
    @Volatile
    private var turnGate: CountDownLatch? = null

    /** What the user would hear and what the recognizer was asked, in order. */
    private val events = mutableListOf<String>()

    private val json = Json { ignoreUnknownKeys = true }

    private inner class FakeRecognizer : DictationRecognizer {
        var listener: DictationRecognizer.Listener? = null
        var starts = 0

        override fun start(language: String, listener: DictationRecognizer.Listener) {
            starts += 1
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
                    "/api/assistant/settings" -> jsonResponse(
                        """{"configured":true,"provider":"anthropic","model":"claude-haiku","last4":"abcd"}""",
                    )
                    "/api/assistant/turns" -> {
                        val text = json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
                        turnTexts.add(text)
                        turnGate?.await(5, TimeUnit.SECONDS)
                        val reply = JsonPrimitive("Noted: $text")
                        jsonResponse("""{"reply":$reply,"transcript_tail":[]}""")
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

    private fun jsonResponse(body: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun createViewModel(locale: String = "it", withRecognizer: Boolean = true) {
        val client = ApiClient(server.url("/api/").toString()) { null }
        viewModel = AssistantViewModel(
            turns = ApiAssistantRepository(client.create(AssistantApi::class.java)),
            settings = ApiAssistantSettingsRepository(client.create(AssistantSettingsApi::class.java)),
            recognizer = if (withRecognizer) recognizer else null,
            tones = tones,
            locale = MutableStateFlow(locale).also { localeFlow = it },
        )
        awaitState { it.available }
    }

    private fun awaitState(predicate: (AssistantViewModel.UiState) -> Boolean): AssistantViewModel.UiState =
        runBlocking { withTimeout(5_000) { viewModel.uiState.first(predicate) } }

    private val listener get() = recognizer.listener!!

    @Test
    fun `a Dictation listens in the Account's Locale, sounds its start and end, and sends what was heard`() {
        createViewModel(locale = "it")
        viewModel.open()

        viewModel.startDictation()
        assertTrue(viewModel.uiState.value.listening)
        listener.onReadyForSpeech()
        listener.onResult("ho speso 30 euro all'Esselunga con la carta, cibo")

        val state = awaitState { !it.pending && it.lines.size == 2 }
        assertEquals(listOf("start it-IT", "start tone", "end tone"), events)
        assertFalse(state.listening)
        assertEquals(listOf("ho speso 30 euro all'Esselunga con la carta, cibo"), turnTexts.toList())
        assertEquals(
            AssistantViewModel.Line(AssistantViewModel.Speaker.USER, "ho speso 30 euro all'Esselunga con la carta, cibo"),
            state.lines.first(),
        )
    }

    @Test
    fun `a Dictation that heard nothing sends nothing and sounds its end`() {
        createViewModel()
        viewModel.open()

        viewModel.startDictation()
        listener.onReadyForSpeech()
        listener.onNoMatch()

        val state = viewModel.uiState.value
        assertEquals(listOf("start it-IT", "start tone", "end tone"), events)
        assertFalse(state.listening)
        assertFalse(state.dictationFailed)
        assertTrue(state.lines.isEmpty())
        assertTrue(turnTexts.isEmpty())
    }

    @Test
    fun `a Dictation refused the microphone makes no sound and is no failure`() {
        createViewModel()
        viewModel.open()

        viewModel.startDictation()
        listener.onNoMatch()

        val state = viewModel.uiState.value
        assertEquals(listOf("start it-IT"), events)
        assertFalse(state.listening)
        assertFalse(state.dictationFailed)
    }

    @Test
    fun `a Dictation that fails for a reason the user did not choose says so, until the next one starts`() {
        createViewModel()
        viewModel.open()
        viewModel.startDictation()
        listener.onReadyForSpeech()

        listener.onFailure()

        assertTrue(viewModel.uiState.value.dictationFailed)
        assertFalse(viewModel.uiState.value.listening)
        assertEquals(listOf("start it-IT", "start tone", "end tone"), events)
        viewModel.startDictation()
        assertFalse(viewModel.uiState.value.dictationFailed)
    }

    @Test
    fun `typing while a Dictation listens cancels it without sending what it heard`() {
        createViewModel()
        viewModel.open()
        viewModel.startDictation()
        listener.onReadyForSpeech()

        viewModel.onTextChange("h")
        // A recognizer that ends late changes nothing.
        listener.onResult("ho speso 30 euro")

        val state = viewModel.uiState.value
        assertEquals(listOf("start it-IT", "start tone", "cancel"), events)
        assertFalse(state.listening)
        assertEquals("h", state.text)
        assertTrue(state.lines.isEmpty())
        assertTrue(turnTexts.isEmpty())
    }

    @Test
    fun `sending what is typed while a Dictation listens cancels it and sends the box alone`() {
        createViewModel()
        viewModel.open()
        viewModel.onTextChange("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.startDictation()
        listener.onReadyForSpeech()

        viewModel.send()
        listener.onResult("no, erano 35")

        awaitState { !it.pending && it.lines.size == 2 }
        assertEquals(listOf("ho speso 30 euro all'Esselunga con la carta, cibo"), turnTexts.toList())
        assertEquals(listOf("start it-IT", "start tone", "cancel"), events)
        assertFalse(viewModel.uiState.value.listening)
    }

    @Test
    fun `closing the Assistant drops a listening Dictation without sending`() {
        createViewModel()
        viewModel.open()
        viewModel.startDictation()
        listener.onReadyForSpeech()

        viewModel.close()
        listener.onResult("ho speso 30 euro")

        assertEquals(listOf("start it-IT", "start tone", "cancel"), events)
        assertFalse(viewModel.uiState.value.listening)
        assertTrue(viewModel.uiState.value.lines.isEmpty())
        assertTrue(turnTexts.isEmpty())
    }

    @Test
    fun `what was heard is sent after anything already typed in the box`() {
        createViewModel()
        viewModel.open()
        viewModel.onTextChange("ho speso 30 euro ")
        viewModel.startDictation()
        listener.onReadyForSpeech()

        listener.onResult("all'Esselunga con la carta, cibo")

        awaitState { !it.pending && it.lines.size == 2 }
        assertEquals(listOf("ho speso 30 euro all'Esselunga con la carta, cibo"), turnTexts.toList())
        assertEquals("", viewModel.uiState.value.text)
    }

    @Test
    fun `a second tap ends the Dictation and what was heard is sent`() {
        createViewModel()
        viewModel.open()
        viewModel.startDictation()
        listener.onReadyForSpeech()

        viewModel.stopDictation()
        listener.onResult("lascia stare")

        awaitState { !it.pending && it.lines.size == 2 }
        assertEquals(listOf("start it-IT", "start tone", "stop", "end tone"), events)
        assertEquals(listOf("lascia stare"), turnTexts.toList())
    }

    @Test
    fun `no Dictation starts while a reply is pending`() {
        createViewModel()
        viewModel.open()
        val gate = CountDownLatch(1)
        turnGate = gate
        viewModel.onTextChange("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.send()
        awaitState { it.pending }

        viewModel.startDictation()

        gate.countDown()
        assertEquals(0, recognizer.starts)
        assertFalse(viewModel.uiState.value.listening)
    }

    @Test
    fun `without a recognizer there is no microphone and nothing listens`() {
        createViewModel(withRecognizer = false)
        viewModel.open()

        viewModel.startDictation()

        assertFalse(viewModel.uiState.value.dictationAvailable)
        assertFalse(viewModel.uiState.value.listening)
    }

    @Test
    fun `an Account in English dictates in en-US`() {
        createViewModel(locale = "en")
        viewModel.open()

        viewModel.startDictation()

        assertTrue(viewModel.uiState.value.dictationAvailable)
        assertEquals(listOf("start en-US"), events)
    }

    @Test
    fun `a Locale changed in Settings is the next Dictation's language`() {
        createViewModel(locale = "en")
        viewModel.open()

        localeFlow.value = "it"
        viewModel.startDictation()

        assertEquals(listOf("start it-IT"), events)
    }

    @Test
    fun `a closed Assistant starts no Dictation`() {
        createViewModel()
        viewModel.open()
        viewModel.close()

        // e.g. the microphone permission granted after the Assistant closed
        viewModel.startDictation()

        assertEquals(0, recognizer.starts)
        assertFalse(viewModel.uiState.value.listening)
    }
}
