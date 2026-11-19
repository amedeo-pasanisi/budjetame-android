package com.budjetame.android.data.wallet

import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.WalletApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The Wallets repository against the shared backend's wire contract. */
class WalletRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ApiWalletRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = ApiClient(server.url("/api/").toString()) { null }
        repository = ApiWalletRepository(client.create(WalletApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `freezing a Wallet posts to its freeze action, never DELETE`() = runBlocking {
        // Since web v1.10.0 (ADR-0034) DELETE /wallets/{id} is the real delete.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":7,"name":"Old card","type":"credit_card","balance":"0.00",""" +
                    """"frozen":true,"created_at":"2026-01-01T00:00:00Z"}""",
            ),
        )

        repository.freezeWallet(7)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/wallets/7/freeze", request.path)
    }

    @Test
    fun `a Wallet not at zero surfaces the server's refusal`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("""{"detail":"A Wallet can only be frozen at a balance of exactly €0"}"""),
        )

        try {
            repository.freezeWallet(7)
            fail("expected the refusal")
        } catch (error: ApiException) {
            assertEquals(422, error.status)
            assertEquals("A Wallet can only be frozen at a balance of exactly €0", error.detail)
        }
    }
}
