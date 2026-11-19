package com.budjetame.android.ui.assistant

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AssistantApi
import com.budjetame.android.data.api.AssistantSettingsApi
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.assistant.ApiAssistantRepository
import com.budjetame.android.data.assistant.ApiAssistantSettingsRepository
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
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
 * The Assistant tested at the single seam (the HTTP API): the ViewModel is
 * driven through the real repositories, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher fakes /assistant/settings (whether the
 * Account has an Assistant) and /assistant/turns.
 */
class AssistantViewModelTest {

    private companion object {
        const val HOURLY_DETAIL = "You have used the 30 Assistant turns of this hour: try again in 1200 seconds."
    }

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AssistantViewModel

    private val calls = ConcurrentLinkedQueue<RecordedCall>()

    /** The fake server's Assistant Settings: configured or not. */
    @Volatile
    private var configured = true

    /** The status the fake answers a turn with. */
    @Volatile
    private var turnStatus = 200

    /** When set, a turn's answer waits for it: the reply is pending. */
    @Volatile
    private var turnGate: CountDownLatch? = null

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        calls.clear()
        configured = true
        turnStatus = 200
        turnGate = null
        // Installed before any ViewModel exists: its init fetches the
        // status, which must never race an empty queue.
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
        viewModel = AssistantViewModel(
            turns = ApiAssistantRepository(client.create(AssistantApi::class.java)),
            settings = ApiAssistantSettingsRepository(client.create(AssistantSettingsApi::class.java)),
        )
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))
        return when {
            method == "GET" && path == "/api/assistant/settings" -> jsonResponse(
                200,
                if (configured) {
                    """{"configured":true,"provider":"anthropic","model":"claude-haiku","last4":"abcd"}"""
                } else {
                    """{"configured":false}"""
                },
            )
            method == "POST" && path == "/api/assistant/turns" -> when (val status = turnStatus) {
                200 -> {
                    turnGate?.await(5, TimeUnit.SECONDS)
                    turnResponse(body)
                }
                409 -> jsonResponse(409, """{"detail":"No Assistant Settings"}""")
                429 -> jsonResponse(429, """{"detail":"$HOURLY_DETAIL"}""").setHeader("Retry-After", "1200")
                else -> jsonResponse(status, """{"detail":"Assistant provider error: boom"}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** A turn as the server answers it: the reply, and a tail of the user
     * message, a tool call, its result and the written reply — the blocks
     * the client must carry back untouched. */
    private fun turnResponse(body: String): MockResponse {
        val text = json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
        val reply = "Noted: $text"
        val n = calls.count { it.path == "/api/assistant/turns" }
        val tail = """[
            {"role":"user","content":[{"type":"text","text":${JsonPrimitive(text)}}]},
            {"role":"assistant","content":[{"type":"tool_call","id":"call-$n","name":"create_draft_transaction","input":{"amount":"30"}}]},
            {"role":"user","content":[{"type":"tool_result","tool_call_id":"call-$n","content":"{\"id\": $n}","is_error":false}]},
            {"role":"assistant","content":[{"type":"text","text":${JsonPrimitive(reply)}}]}
        ]"""
        return jsonResponse(200, """{"reply":${JsonPrimitive(reply)},"transcript_tail":$tail}""")
    }

    private fun turnBodies(): List<JsonObject> = calls
        .filter { it.method == "POST" && it.path == "/api/assistant/turns" }
        .map { json.parseToJsonElement(it.body).jsonObject }

    private fun jsonResponse(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun awaitState(predicate: (AssistantViewModel.UiState) -> Boolean): AssistantViewModel.UiState =
        runBlocking { withTimeout(5_000) { viewModel.uiState.first(predicate) } }

    @Test
    fun `the Assistant is available when the Account has Assistant Settings`() {
        createViewModel()

        val state = awaitState { it.availabilityKnown }

        assertTrue(state.available)
    }

    @Test
    fun `the Assistant is absent without Assistant Settings and appears once they are saved`() {
        configured = false
        createViewModel()
        assertFalse(awaitState { it.availabilityKnown }.available)

        configured = true
        viewModel.refreshAvailability()

        awaitState { it.available }
    }

    @Test
    fun `a typed sentence is sent as a turn and its reply shows under it`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()

        viewModel.onTextChange("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.send()

        val state = awaitState { !it.pending && it.lines.size == 2 }
        assertEquals(
            listOf(
                AssistantViewModel.Line(AssistantViewModel.Speaker.USER, "ho speso 30 euro all'Esselunga con la carta, cibo"),
                AssistantViewModel.Line(AssistantViewModel.Speaker.ASSISTANT, "Noted: ho speso 30 euro all'Esselunga con la carta, cibo"),
            ),
            state.lines,
        )
        assertEquals("", state.text)
        val sent = turnBodies().single()
        assertEquals(JsonArray(emptyList()), sent.getValue("transcript").jsonArray)
        assertEquals("ho speso 30 euro all'Esselunga con la carta, cibo", sent.getValue("text").jsonPrimitive.content)
    }

    @Test
    fun `the next turn carries the transcript as last received, tool blocks included`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        viewModel.onTextChange("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.send()
        awaitState { !it.pending && it.lines.size == 2 }

        viewModel.onTextChange("no, erano 35")
        viewModel.send()
        awaitState { !it.pending && it.lines.size == 4 }

        val (first, second) = turnBodies()
        val transcript = second.getValue("transcript").jsonArray
        assertEquals(4, transcript.size)
        assertEquals(
            "create_draft_transaction",
            transcript[1].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertEquals(
            "ho speso 30 euro all'Esselunga con la carta, cibo",
            transcript[0].jsonObject.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content,
        )
        assertEquals("no, erano 35", second.getValue("text").jsonPrimitive.content)
        assertEquals(JsonArray(emptyList()), first.getValue("transcript").jsonArray)
    }

    @Test
    fun `a blank box sends nothing`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()

        viewModel.onTextChange("   ")
        viewModel.send()

        assertTrue(turnBodies().isEmpty())
        assertTrue(viewModel.uiState.value.lines.isEmpty())
    }

    private fun sendAndAwait(sentence: String) {
        val before = viewModel.uiState.value.lines.size
        viewModel.onTextChange(sentence)
        viewModel.send()
        awaitState { !it.pending && it.lines.size > before }
    }

    @Test
    fun `a turn bumps the data version so the Transactions tab reloads its Drafts`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        val before = DataVersion.current()

        sendAndAwait("ho speso 30 euro all'Esselunga con la carta, cibo")

        assertTrue(DataVersion.current() > before)
    }

    @Test
    fun `the hourly bound shows the wait the server names, and the sentence goes back into the box`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        sendAndAwait("ho speso 30 euro all'Esselunga con la carta, cibo")
        turnStatus = 429

        sendAndAwait("no, erano 35")

        val state = viewModel.uiState.value
        assertEquals(
            AssistantViewModel.Line(AssistantViewModel.Speaker.ASSISTANT, HOURLY_DETAIL),
            state.lines.last(),
        )
        assertEquals(3, state.lines.size)
        assertEquals("no, erano 35", state.text)
        // The refused turn is not in the transcript: a retry carries the
        // first turn only.
        turnStatus = 200
        sendAndAwait("no, erano 35")
        assertEquals(4, turnBodies().last().getValue("transcript").jsonArray.size)
    }

    @Test
    fun `a turn the server could not answer says so on a failure line and keeps the sentence`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        turnStatus = 502

        sendAndAwait("ho speso 30 euro all'Esselunga con la carta, cibo")

        val state = viewModel.uiState.value
        assertEquals(
            listOf(
                AssistantViewModel.Line(
                    AssistantViewModel.Speaker.ASSISTANT,
                    "The Assistant could not answer. Try again.",
                    failure = true,
                ),
            ),
            state.lines,
        )
        assertEquals("ho speso 30 euro all'Esselunga con la carta, cibo", state.text)
    }

    @Test
    fun `a turn answered 409 closes the Assistant and hides it`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        turnStatus = 409

        viewModel.onTextChange("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.send()

        val state = awaitState { !it.available }
        assertFalse(state.open)
        assertTrue(state.lines.isEmpty())
    }

    @Test
    fun `closing the Assistant forgets the conversation`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        sendAndAwait("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.onTextChange("half typed")

        viewModel.close()
        viewModel.open()

        val state = viewModel.uiState.value
        assertTrue(state.open)
        assertTrue(state.lines.isEmpty())
        assertEquals("", state.text)
        sendAndAwait("lascia stare")
        assertEquals(JsonArray(emptyList()), turnBodies().last().getValue("transcript").jsonArray)
    }

    @Test
    fun `nothing is sent while a reply is pending`() {
        createViewModel()
        awaitState { it.available }
        viewModel.open()
        val gate = CountDownLatch(1)
        turnGate = gate
        viewModel.onTextChange("ho speso 30 euro all'Esselunga con la carta, cibo")
        viewModel.send()
        awaitState { it.pending }

        viewModel.onTextChange("no, erano 35")
        viewModel.send()
        gate.countDown()

        val state = awaitState { !it.pending && it.lines.size == 2 }
        assertEquals(1, turnBodies().size)
        assertEquals("no, erano 35", state.text)
    }
}
