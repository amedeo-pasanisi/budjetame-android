package com.budjetame.android.data.auth

import com.budjetame.android.InMemoryTokenStorage
import com.budjetame.android.data.Session
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.locale.LastLocaleStore
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Account's Locale against the shared backend's wire contract (issue #59). */
class AuthRepositoryLocaleTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ApiAuthRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val session = Session(InMemoryTokenStorage()).apply { save("token") }
        val client = ApiClient(server.url("/api/").toString()) { session.token }
        repository = ApiAuthRepository(client.create(AuthApi::class.java), session)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `the Locale is the language of the auth-me response the app already reads`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":1,"email":"a@b.it","language":"it"}"""))

        repository.fetchCurrentAccount()

        assertEquals("it", repository.locale.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `changing the Locale puts the language on the Account and publishes it`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))

        repository.changeLocale("it")

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/auth/me/language", request.path)
        assertEquals("""{"language":"it"}""", request.body.readUtf8())
        assertEquals("it", repository.locale.value)
    }

    @Test
    fun `a failed change keeps the previous Locale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        try {
            repository.changeLocale("it")
            fail("expected the failure")
        } catch (error: ApiException) {
            assertEquals(500, error.status)
        }
        assertEquals("en", repository.locale.value)
    }

    // --- The last known Locale on the device (issue #63) ---

    /** A device's last-Locale record, in memory. */
    private class FakeLastLocaleStore(var stored: String? = null) : LastLocaleStore {
        override fun read(): String? = stored
        override fun write(code: String) {
            stored = code
        }
    }

    private fun repositoryWith(store: LastLocaleStore, device: Locale): ApiAuthRepository {
        val session = Session(InMemoryTokenStorage()).apply { save("token") }
        val client = ApiClient(server.url("/api/").toString()) { session.token }
        return ApiAuthRepository(client.create(AuthApi::class.java), session, store, device)
    }

    @Test
    fun `before any Account is read the Locale is the last one stored on the device`() {
        val repository = repositoryWith(FakeLastLocaleStore("it"), device = Locale.US)

        assertEquals("it", repository.locale.value)
    }

    @Test
    fun `with nothing stored the Locale follows the device language`() {
        assertEquals("it", repositoryWith(FakeLastLocaleStore(), device = Locale.ITALY).locale.value)
        assertEquals("en", repositoryWith(FakeLastLocaleStore(), device = Locale.GERMANY).locale.value)
    }

    @Test
    fun `every published Locale is stored and sign-out keeps it for the login screen`() = runBlocking {
        val store = FakeLastLocaleStore()
        val repository = repositoryWith(store, device = Locale.US)
        server.enqueue(MockResponse().setBody("""{"id":1,"email":"a@b.it","language":"it"}"""))

        repository.fetchCurrentAccount()
        repository.signOut()

        assertEquals("it", store.stored)
        assertEquals("it", repository.locale.value)
    }

    @Test
    fun `a Settings change is stored too`() = runBlocking {
        val store = FakeLastLocaleStore("it")
        val repository = repositoryWith(store, device = Locale.ITALY)
        server.enqueue(MockResponse().setResponseCode(204))

        repository.changeLocale("en")

        assertEquals("en", store.stored)
    }
}
