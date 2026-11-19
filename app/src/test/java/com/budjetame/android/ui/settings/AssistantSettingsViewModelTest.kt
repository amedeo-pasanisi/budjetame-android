package com.budjetame.android.ui.settings

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AssistantProvider
import com.budjetame.android.data.api.AssistantSettingsApi
import com.budjetame.android.data.assistant.ApiAssistantSettingsRepository
import com.budjetame.android.ui.common.english
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Assistant Settings block tested at the single seam (the HTTP API): the
 * ViewModel is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of
 * /assistant/settings and /assistant/models — the key goes in through PUT
 * and only its last four characters come back, as on the backend.
 */
class AssistantSettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(
        val method: String,
        val path: String,
        val query: String?,
        val keyHeader: String?,
        val body: String,
    )

    private lateinit var server: MockWebServer
    private lateinit var viewModel: AssistantSettingsViewModel

    private val calls = ConcurrentLinkedQueue<RecordedCall>()

    // The fake server's stored settings.
    private var storedProvider: String? = null
    private var storedKey: String? = null
    private var storedModel: String? = null

    /** When set, PUT answers 422 with this detail. */
    private var saveRejection: String? = null

    /** When set, GET /assistant/models answers with this status. */
    private var modelsStatus = 200

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        calls.clear()
        storedProvider = null
        storedKey = null
        storedModel = null
        saveRejection = null
        modelsStatus = 200
        // The dispatcher is installed before any ViewModel exists, so no
        // request can race an empty queue.
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
        val repository = ApiAssistantSettingsRepository(client.create(AssistantSettingsApi::class.java))
        viewModel = AssistantSettingsViewModel(repository)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val url = request.requestUrl
        val path = url?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, url?.query, request.getHeader("X-Assistant-Key"), body))

        return when {
            method == "GET" && path == "/api/assistant/settings" -> jsonResponse(200, statusJson())

            method == "PUT" && path == "/api/assistant/settings" -> {
                val rejection = saveRejection
                if (rejection != null) {
                    jsonResponse(422, """{"detail":"$rejection"}""")
                } else {
                    val sent = json.parseToJsonElement(body).jsonObject
                    storedProvider = sent.getValue("provider").jsonPrimitive.content
                    storedKey = sent.getValue("key").jsonPrimitive.content
                    val model = sent["model"]
                    storedModel = if (model == null || model is JsonNull) {
                        defaultModel(storedProvider!!)
                    } else {
                        model.jsonPrimitive.content
                    }
                    jsonResponse(200, statusJson())
                }
            }

            method == "DELETE" && path == "/api/assistant/settings" -> {
                storedProvider = null
                storedKey = null
                storedModel = null
                MockResponse().setResponseCode(204)
            }

            method == "GET" && path == "/api/assistant/models" -> {
                val provider = url?.queryParameter("provider").orEmpty()
                when {
                    modelsStatus != 200 -> jsonResponse(modelsStatus, """{"detail":"no list"}""")
                    provider == "gemini" && request.getHeader("X-Assistant-Key") == null &&
                        storedProvider != "gemini" ->
                        jsonResponse(409, """{"detail":"Google Gemini lists its models with a key"}""")
                    else -> jsonResponse(200, modelsJson(provider))
                }
            }

            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun defaultModel(provider: String) = when (provider) {
        "anthropic" -> "claude-haiku"
        "openrouter" -> "openrouter/auto"
        else -> "gemini-flash"
    }

    private fun statusJson(): String {
        val key = storedKey
        return if (key == null) {
            """{"configured":false,"provider":null,"model":null,"last4":null}"""
        } else {
            """{"configured":true,"provider":"$storedProvider","model":"$storedModel","last4":"${key.takeLast(4)}"}"""
        }
    }

    private fun modelsJson(provider: String): String = when (provider) {
        "anthropic" ->
            """{"provider":"anthropic","models":[
                {"id":"claude-haiku","label":"Claude Haiku","free":false},
                {"id":"claude-sonnet","label":"Claude Sonnet","free":false}]}"""
        "openrouter" ->
            """{"provider":"openrouter","models":[
                {"id":"meta/llama:free","label":"Llama","free":true}]}"""
        else ->
            """{"provider":"gemini","models":[
                {"id":"gemini-flash","label":"gemini-flash","free":false}]}"""
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private suspend fun awaitState(predicate: (AssistantSettingsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun lastCall(method: String, path: String): RecordedCall =
        calls.toList().last { it.method == method && it.path == path }

    // --- Empty state ---

    @Test
    fun `with no Assistant Settings the block offers the form and the default provider's models`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { !it.loading && it.catalogue == CatalogueState.READY }

        val state = viewModel.uiState.value
        assertFalse(state.configured)
        assertTrue(state.editing)
        assertEquals(AssistantProvider.ANTHROPIC, state.provider)
        assertEquals(listOf("claude-haiku", "claude-sonnet"), state.models.map { it.id })
        val listing = lastCall("GET", "/api/assistant/models")
        assertEquals("provider=anthropic", listing.query)
        assertNull(listing.keyHeader)
    }

    // --- Save ---

    @Test
    fun `save stores provider key and model, then shows only provider model and the key's last characters`() =
        runBlocking {
            createViewModel()
            viewModel.open()
            awaitState { !it.loading }

            viewModel.onKeyChange("sk-ant-secret-9876")
            viewModel.onModelChange("claude-sonnet")
            viewModel.save()
            awaitState { it.configured && !it.editing }

            val sent = json.parseToJsonElement(lastCall("PUT", "/api/assistant/settings").body).jsonObject
            assertEquals(JsonPrimitive("anthropic"), sent["provider"])
            assertEquals(JsonPrimitive("sk-ant-secret-9876"), sent["key"])
            assertEquals(JsonPrimitive("claude-sonnet"), sent["model"])
            val state = viewModel.uiState.value
            assertEquals(AssistantProvider.ANTHROPIC, state.status?.provider)
            assertEquals("claude-sonnet", state.status?.model)
            assertEquals("9876", state.status?.last4)
            // The full key is dropped from the client once saved.
            assertEquals("", state.key)
        }

    @Test
    fun `an empty model is sent as null so the provider's default is stored`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }

        viewModel.onKeyChange("sk-ant-secret-9876")
        viewModel.save()
        awaitState { it.configured }

        val sent = json.parseToJsonElement(lastCall("PUT", "/api/assistant/settings").body).jsonObject
        assertEquals(JsonNull, sent["model"])
        assertEquals("claude-haiku", viewModel.uiState.value.status?.model)
    }

    @Test
    fun `save without a key asks for it and sends nothing`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }

        viewModel.onKeyChange("   ")
        viewModel.save()

        assertEquals("Enter the Assistant Key.", viewModel.uiState.value.keyError?.english())
        assertTrue(calls.none { it.method == "PUT" })
    }

    @Test
    fun `a rejected save shows the server's message in the block`() = runBlocking {
        saveRejection = "The Assistant Key is empty"
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }

        viewModel.onKeyChange("sk-ant-secret-9876")
        viewModel.save()
        awaitState { it.error != null }

        val state = viewModel.uiState.value
        assertEquals("The Assistant Key is empty", state.error?.english())
        assertFalse(state.configured)
        assertTrue(state.editing)
        assertFalse(state.saving)
    }

    // --- Configured: Replace and Remove ---

    private fun seedSettings(provider: String, key: String, model: String) {
        storedProvider = provider
        storedKey = key
        storedModel = model
    }

    @Test
    fun `with saved settings the block shows only the configured status`() = runBlocking {
        seedSettings("openrouter", "sk-or-secret-4321", "meta/llama:free")
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }

        val state = viewModel.uiState.value
        assertTrue(state.configured)
        assertFalse(state.editing)
        assertEquals(AssistantProvider.OPENROUTER, state.status?.provider)
        assertEquals("meta/llama:free", state.status?.model)
        assertEquals("4321", state.status?.last4)
        assertEquals("", state.key)
    }

    @Test
    fun `replace opens the form on the configured provider and model with an empty key`() = runBlocking {
        seedSettings("openrouter", "sk-or-secret-4321", "meta/llama:free")
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }

        viewModel.startReplace()
        awaitState { it.editing && it.catalogue == CatalogueState.READY }

        val state = viewModel.uiState.value
        assertEquals(AssistantProvider.OPENROUTER, state.provider)
        assertEquals("meta/llama:free", state.model)
        assertEquals("", state.key)
        assertEquals(listOf("meta/llama:free"), state.models.map { it.id })
        assertEquals("provider=openrouter", lastCall("GET", "/api/assistant/models").query)

        viewModel.cancelReplace()
        assertFalse(viewModel.uiState.value.editing)
    }

    @Test
    fun `remove deletes the Assistant Settings and returns the block to the empty state`() = runBlocking {
        seedSettings("openrouter", "sk-or-secret-4321", "meta/llama:free")
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }

        viewModel.remove()
        awaitState { !it.configured && it.editing }

        assertTrue(calls.any { it.method == "DELETE" && it.path == "/api/assistant/settings" })
        assertNull(storedKey)
        assertEquals("", viewModel.uiState.value.key)
        assertEquals("", viewModel.uiState.value.model)
    }

    @Test
    fun `closing the dialog drops a key typed but not saved`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }
        viewModel.onKeyChange("sk-ant-typed-0000")

        viewModel.close()
        assertEquals("", viewModel.uiState.value.key)

        viewModel.open()
        awaitState { !it.loading }

        assertEquals("", viewModel.uiState.value.key)
    }

    @Test
    fun `closing the dialog while a save is in flight leaves the block empty`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { !it.loading }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                route(request).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        viewModel.onKeyChange("sk-ant-secret-9876")
        viewModel.save()

        viewModel.close()
        kotlinx.coroutines.delay(600)

        assertNull(viewModel.uiState.value.status)
    }

    // --- Provider and model list ---

    @Test
    fun `switching provider resets the model and lists the new provider's models`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { it.catalogue == CatalogueState.READY }
        viewModel.onModelChange("claude-sonnet")

        viewModel.onProviderChange(AssistantProvider.OPENROUTER)
        awaitState { it.catalogue == CatalogueState.READY && it.provider == AssistantProvider.OPENROUTER }

        val state = viewModel.uiState.value
        assertEquals("", state.model)
        assertEquals(listOf("meta/llama:free"), state.models.map { it.id })
        assertTrue(state.models.single().free)
        assertEquals("provider=openrouter", lastCall("GET", "/api/assistant/models").query)
    }

    @Test
    fun `a provider that lists only with a key is listed with the unsaved key once the key field is left`() =
        runBlocking {
            createViewModel()
            viewModel.open()
            awaitState { it.catalogue == CatalogueState.READY }

            viewModel.onProviderChange(AssistantProvider.GEMINI)
            awaitState { it.catalogue == CatalogueState.KEY_NEEDED }
            assertTrue(viewModel.uiState.value.models.isEmpty())

            viewModel.onKeyChange(" AIza-unsaved-1111 ")
            viewModel.onKeyFieldLeft()
            awaitState { it.catalogue == CatalogueState.READY }

            assertEquals(listOf("gemini-flash"), viewModel.uiState.value.models.map { it.id })
            val listing = lastCall("GET", "/api/assistant/models")
            assertEquals("provider=gemini", listing.query)
            assertEquals("AIza-unsaved-1111", listing.keyHeader)
            // The key never travels in the query string.
            assertFalse(listing.query.orEmpty().contains("AIza"))
        }

    @Test
    fun `switching provider drops a key typed for the previous one so it never reaches the new provider`() =
        runBlocking {
            createViewModel()
            viewModel.open()
            awaitState { it.catalogue == CatalogueState.READY }
            viewModel.onKeyChange("sk-ant-unsaved-2222")
            viewModel.onKeyFieldLeft()

            viewModel.onProviderChange(AssistantProvider.GEMINI)
            awaitState { it.catalogue == CatalogueState.KEY_NEEDED && it.provider == AssistantProvider.GEMINI }

            assertEquals("", viewModel.uiState.value.key)
            assertTrue(calls.none { it.keyHeader != null })
        }
    @Test
    fun `a typed model id outside the list is saved as typed`() = runBlocking {
        createViewModel()
        viewModel.open()
        awaitState { it.catalogue == CatalogueState.READY }

        viewModel.onKeyChange("sk-ant-secret-9876")
        viewModel.onModelChange("claude-experimental-2")
        viewModel.save()
        awaitState { it.configured }

        val sent = json.parseToJsonElement(lastCall("PUT", "/api/assistant/settings").body).jsonObject
        assertEquals(JsonPrimitive("claude-experimental-2"), sent["model"])
        assertEquals("claude-experimental-2", viewModel.uiState.value.status?.model)
    }

    @Test
    fun `an unavailable model list says so and still lets a typed model be saved`() = runBlocking {
        modelsStatus = 502
        createViewModel()
        viewModel.open()
        awaitState { it.catalogue == CatalogueState.ERROR }
        assertTrue(viewModel.uiState.value.models.isEmpty())

        viewModel.onKeyChange("sk-ant-secret-9876")
        viewModel.onModelChange("claude-haiku")
        viewModel.save()
        awaitState { it.configured }

        assertEquals("claude-haiku", viewModel.uiState.value.status?.model)
    }

    @Test
    fun `a key the provider rejects while listing is reported under the model picker`() = runBlocking {
        modelsStatus = 422
        createViewModel()
        viewModel.open()

        awaitState { it.catalogue == CatalogueState.KEY_REJECTED }
    }
}
