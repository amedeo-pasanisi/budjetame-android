package com.budjetame.android.ui.settings

import com.budjetame.android.InMemoryTokenStorage
import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.Session
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.auth.ApiAuthRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The Settings dialog's Language choice (issue #59), driven through the real
 * auth repository — the app's shared Locale — against a MockWebServer.
 */
class LanguageSettingsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private lateinit var server: MockWebServer
    private lateinit var repository: ApiAuthRepository
    private lateinit var viewModel: LanguageSettingsViewModel

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val session = Session(InMemoryTokenStorage()).apply { save("token") }
        val client = ApiClient(server.url("/api/").toString()) { session.token }
        repository = ApiAuthRepository(client.create(AuthApi::class.java), session)
        viewModel = LanguageSettingsViewModel(repository)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the choice shows the Account's current Locale`() {
        assertEquals("en", viewModel.uiState.value.locale)
    }

    @Test
    fun `choosing Italiano puts it on the Account and updates the shared Locale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        viewModel.choose("it")
        val state = withTimeout(5_000) { viewModel.uiState.first { it.locale == "it" && !it.saving } }

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/auth/me/language", request.path)
        assertEquals("""{"language":"it"}""", request.body.readUtf8())
        assertEquals("it", repository.locale.value)
        assertNull(state.error)
    }

    @Test
    fun `a failed PUT shows an error and keeps the previous Locale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        viewModel.choose("it")
        val state = withTimeout(5_000) { viewModel.uiState.first { it.error != null } }

        assertEquals("Could not change the language. Please try again.", state.error)
        assertEquals("en", state.locale)
        assertEquals("en", repository.locale.value)
    }

    @Test
    fun `choosing the current Locale sends nothing`() {
        viewModel.choose("en")

        assertEquals(0, server.requestCount)
    }
}
