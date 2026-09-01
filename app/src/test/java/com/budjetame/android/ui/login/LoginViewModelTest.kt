package com.budjetame.android.ui.login

import com.budjetame.android.InMemoryTokenStorage
import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.Session
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.auth.ApiAuthRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The login flow tested at the single seam (the HTTP API): the ViewModel is
 * driven through the real repository, Retrofit, OkHttp, and a MockWebServer
 * whose Dispatcher routes by path — so concurrent requests (the config fetch
 * and a submit) can never race for queued responses.
 */
class LoginViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var storage: InMemoryTokenStorage
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = InMemoryTokenStorage()
        val client = ApiClient(server.url("/api/").toString()) { null }
        val repository = ApiAuthRepository(client.create(AuthApi::class.java), Session(storage))
        viewModel = LoginViewModel(repository)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private val emptyConfig = json("""{"google_client_id":""}""")

    private fun serve(routes: Map<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.path] ?: MockResponse().setResponseCode(404)
        }
    }

    private suspend fun awaitOutcome() {
        withTimeout(5_000) {
            viewModel.uiState.first { it.account != null || it.error != null || it.resetSent }
        }
    }

    @Test
    fun `sign-in success stores the token and exposes the account`() = runBlocking {
        serve(
            mapOf(
                "/api/auth/config" to emptyConfig,
                "/api/auth/login" to json("""{"access_token":"tok-1","token_type":"bearer"}"""),
                "/api/auth/me" to json("""{"id":1,"email":"me@example.com"}"""),
            ),
        )
        viewModel.onEmailChange("me@example.com")
        viewModel.onPasswordChange("hunter22")
        viewModel.submit()
        awaitOutcome()

        assertEquals("me@example.com", viewModel.uiState.value.account?.email)
        assertEquals("tok-1", storage.stored)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `sign-in 401 shows the web's exact message`() = runBlocking {
        serve(
            mapOf(
                "/api/auth/config" to emptyConfig,
                "/api/auth/login" to MockResponse().setResponseCode(401)
                    .setBody("""{"detail":"Invalid credentials"}"""),
            ),
        )
        viewModel.onEmailChange("me@example.com")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()
        awaitOutcome()

        assertEquals("Incorrect email or password.", viewModel.uiState.value.error)
        assertNull(storage.stored)
    }

    @Test
    fun `sign-up 409 shows the web's exact message`() = runBlocking {
        serve(
            mapOf(
                "/api/auth/config" to emptyConfig,
                "/api/auth/register" to MockResponse().setResponseCode(409)
                    .setBody("""{"detail":"Email already registered"}"""),
            ),
        )
        viewModel.switchMode(LoginViewModel.Mode.SignUp)
        viewModel.onEmailChange("me@example.com")
        viewModel.onPasswordChange("hunter22")
        viewModel.submit()
        awaitOutcome()

        assertEquals("An Account with this email already exists.", viewModel.uiState.value.error)
    }

    @Test
    fun `forgot-password success shows the inbox copy`() = runBlocking {
        serve(
            mapOf(
                "/api/auth/config" to emptyConfig,
                "/api/auth/forgot-password" to MockResponse().setResponseCode(204),
            ),
        )
        viewModel.switchMode(LoginViewModel.Mode.Forgot)
        viewModel.onEmailChange("me@example.com")
        viewModel.submit()
        awaitOutcome()

        assertEquals(true, viewModel.uiState.value.resetSent)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a non-empty google client id enables the button`() = runBlocking {
        serve(mapOf("/api/auth/config" to json("""{"google_client_id":"apps.example.com"}""")))
        withTimeout(5_000) {
            viewModel.uiState.first { it.googleClientId != null }
        }
        assertEquals("apps.example.com", viewModel.uiState.value.googleClientId)
    }

    @Test
    fun `an empty google client id keeps the button hidden`() = runBlocking {
        serve(mapOf("/api/auth/config" to emptyConfig))
        withTimeout(5_000) {
            viewModel.uiState.first { it.googleClientId != null }
        }
        // The raw value is recorded; the button hides on blank.
        assertEquals("", viewModel.uiState.value.googleClientId)
    }

    @Test
    fun `the submit button is gated on the visible fields`() {
        viewModel.onEmailChange("")
        assertEquals(false, viewModel.uiState.value.canSubmit)
        viewModel.onEmailChange("me@example.com")
        assertEquals(false, viewModel.uiState.value.canSubmit) // password still empty
        viewModel.onPasswordChange("hunter22")
        assertEquals(true, viewModel.uiState.value.canSubmit)
        viewModel.switchMode(LoginViewModel.Mode.Forgot)
        assertEquals(true, viewModel.uiState.value.canSubmit) // forgot needs only email
    }
}
