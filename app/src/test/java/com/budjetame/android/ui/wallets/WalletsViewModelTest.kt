package com.budjetame.android.ui.wallets

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletCreateRequest
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.api.WalletUpdateRequest
import com.budjetame.android.data.wallet.ApiWalletRepository
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
 * The Wallets flow tested at the single seam (the HTTP API): the ViewModel is
 * driven through the real repository, Retrofit, OkHttp, and a MockWebServer
 * whose dispatcher is a small stateful fake of the /wallets resource — so a
 * write followed by the ADR-0002 background refetch reads back a consistent
 * list. Request bodies are captured for assertions.
 */
class WalletsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: WalletsViewModel

    private val store = mutableListOf<WalletDto>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var nextId = 1
    private var listStatus = 200
    private var createStatus = 201
    private var renameStatus = 200

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store.clear()
        calls.clear()
        nextId = 1
        listStatus = 200
        createStatus = 201
        renameStatus = 200
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
        val repository = ApiWalletRepository(client.create(WalletApi::class.java))
        viewModel = WalletsViewModel(repository)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))

        return when {
            method == "GET" && path == "/api/wallets" -> when {
                listStatus != 200 -> jsonResponse(listStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(store))
            }

            method == "POST" && path == "/api/wallets" -> when {
                createStatus != 201 ->
                    jsonResponse(createStatus, """{"detail":"A Wallet with this name already exists"}""")
                else -> {
                    val create = json.decodeFromString<WalletCreateRequest>(body)
                    val wallet = WalletDto(
                        id = nextId++,
                        name = create.name,
                        type = create.type,
                        balance = create.opening_balance,
                        frozen = false,
                        created_at = "2026-08-01T10:00:00Z",
                    )
                    store.add(wallet)
                    jsonResponse(201, json.encodeToString(wallet))
                }
            }

            method == "PATCH" && path.matches(Regex("/api/wallets/\\d+")) -> when {
                renameStatus != 200 ->
                    jsonResponse(renameStatus, """{"detail":"A Wallet with this name already exists"}""")
                else -> {
                    val id = path.removePrefix("/api/wallets/").toInt()
                    val update = json.decodeFromString<WalletUpdateRequest>(body)
                    val index = store.indexOfFirst { it.id == id }
                    val updated = store[index].copy(name = update.name)
                    store[index] = updated
                    jsonResponse(200, json.encodeToString(updated))
                }
            }

            method == "DELETE" && path.matches(Regex("/api/wallets/\\d+")) -> {
                val id = path.removePrefix("/api/wallets/").toInt()
                val index = store.indexOfFirst { it.id == id }
                store[index] = store[index].copy(frozen = true)
                MockResponse().setResponseCode(204)
            }

            method == "POST" && path.matches(Regex("/api/wallets/\\d+/unfreeze")) -> {
                val id = path.removePrefix("/api/wallets/").removeSuffix("/unfreeze").toInt()
                val index = store.indexOfFirst { it.id == id }
                val unfrozen = store[index].copy(frozen = false)
                store[index] = unfrozen
                jsonResponse(200, json.encodeToString(unfrozen))
            }

            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun seed(vararg wallets: WalletDto) {
        store.addAll(wallets)
        wallets.forEach { nextId = maxOf(nextId, it.id + 1) }
    }

    private fun wallet(
        id: Int,
        name: String,
        type: WalletType,
        balance: String,
        frozen: Boolean = false,
    ) = WalletDto(id, name, type, balance, frozen, "2026-08-01T10:00:00Z")

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (WalletsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

    // --- Load: sections, sorting, frozen separation ---

    @Test
    fun `wallets group into fixed sections sorted case-insensitively with frozen separated`() = runBlocking {
        seed(
            wallet(1, "zara", WalletType.CONTACT, "50.00"),
            wallet(2, "Intesa", WalletType.CHECKING, "1200.00"),
            wallet(3, "anna", WalletType.CONTACT, "-30.00"),
            wallet(4, "Marco", WalletType.CONTACT, "10.00"),
            wallet(5, "Amex", WalletType.CREDIT_CARD, "-250.00"),
            wallet(6, "Leo", WalletType.CONTACT, "0.00"),
            wallet(7, "Old Card", WalletType.CREDIT_CARD, "0.00", frozen = true),
        )
        createViewModel()
        awaitLoaded()

        val state = viewModel.uiState.value
        assertEquals(
            listOf(WalletType.CONTACT, WalletType.CHECKING, WalletType.CREDIT_CARD, WalletType.CASH),
            state.sections.map { it.type },
        )
        assertEquals(
            listOf("Contacts", "Checking Accounts", "Credit Cards", "Cash"),
            state.sections.map { it.label },
        )
        assertEquals(listOf("anna", "Leo", "Marco", "zara"), state.sections[0].items.map { it.name })
        assertEquals(listOf("Intesa"), state.sections[1].items.map { it.name })
        assertEquals(listOf("Amex"), state.sections[2].items.map { it.name })
        assertTrue(state.sections[3].items.isEmpty())
        assertEquals(listOf("Old Card"), state.frozenWallets.map { it.name })
    }

    // --- Create ---

    @Test
    fun `create sends the name type and opening balance and lands the wallet`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Intesa")
        viewModel.onTypeChange(WalletType.CHECKING)
        viewModel.onOpeningBalanceChange("1000.00")
        viewModel.submit()
        awaitState { it.modal == null && it.wallets.any { wallet -> wallet.name == "Intesa" } }

        val create = json.decodeFromString<WalletCreateRequest>(call("POST", "/api/wallets").body)
        assertEquals("Intesa", create.name)
        assertEquals(WalletType.CHECKING, create.type)
        assertEquals("1000.00", create.opening_balance)
        assertEquals("1000.00", viewModel.uiState.value.wallets.first { it.name == "Intesa" }.balance)
    }

    @Test
    fun `create with a blank opening balance sends zero`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Pocket")
        viewModel.onTypeChange(WalletType.CASH)
        viewModel.submit()
        awaitState { it.modal == null && it.wallets.any { wallet -> wallet.name == "Pocket" } }

        val create = json.decodeFromString<WalletCreateRequest>(call("POST", "/api/wallets").body)
        assertEquals("0.00", create.opening_balance)
        assertEquals("0.00", viewModel.uiState.value.wallets.first { it.name == "Pocket" }.balance)
    }

    @Test
    fun `create rejects a negative opening balance before calling the api`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Negative")
        viewModel.onTypeChange(WalletType.CASH)
        viewModel.onOpeningBalanceChange("-1.00")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals("Enter an amount of €0 or more.", viewModel.uiState.value.modal?.error)
        assertTrue(calls.toList().none { it.method == "POST" && it.path == "/api/wallets" })
    }

    @Test
    fun `switching the type to contact clears a drafted opening balance`() {
        createViewModel()
        viewModel.openCreate()
        viewModel.onOpeningBalanceChange("50.00")
        viewModel.onTypeChange(WalletType.CONTACT)
        assertEquals("", viewModel.uiState.value.modal?.openingBalance)
    }

    @Test
    fun `create conflict shows the web's exact message`() = runBlocking {
        createStatus = 409
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Taken")
        viewModel.onTypeChange(WalletType.CASH)
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals("A wallet with this name already exists.", viewModel.uiState.value.modal?.error)
    }

    // --- Rename ---

    @Test
    fun `rename sends only the new name and keeps the type`() = runBlocking {
        seed(wallet(1, "Old Name", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.wallets.first())
        viewModel.onNameChange("New Name")
        viewModel.submit()
        awaitState { it.modal == null && it.wallets.any { wallet -> wallet.name == "New Name" } }

        val patch = call("PATCH", "/api/wallets/1")
        assertFalse(patch.body.contains("type"))
        val update = json.decodeFromString<WalletUpdateRequest>(patch.body)
        assertEquals("New Name", update.name)
        assertEquals(WalletType.CASH, viewModel.uiState.value.wallets.first { it.id == 1 }.type)
    }

    @Test
    fun `rename conflict shows the web's exact message`() = runBlocking {
        renameStatus = 409
        seed(wallet(1, "Mine", WalletType.CASH, "0.00"), wallet(2, "Taken", WalletType.CHECKING, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.wallets.first { it.id == 1 })
        viewModel.onNameChange("TAKEN")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals("A wallet with this name already exists.", viewModel.uiState.value.modal?.error)
    }

    // --- Freeze / unfreeze ---

    @Test
    fun `freeze is only offered when the balance is exactly zero`() = runBlocking {
        seed(wallet(1, "Settled", WalletType.CASH, "0.00"), wallet(2, "Loaded", WalletType.CHECKING, "50.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.wallets.first { it.id == 1 })
        assertTrue(viewModel.uiState.value.modal!!.canFreeze)
        viewModel.closeModal()

        viewModel.openEdit(viewModel.uiState.value.wallets.first { it.id == 2 })
        assertFalse(viewModel.uiState.value.modal!!.canFreeze)
    }

    @Test
    fun `freeze moves a zero-balance wallet into the frozen list after a tap-again confirm`() = runBlocking {
        seed(wallet(1, "To Freeze", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.wallets.first { it.id == 1 })
        viewModel.onFreezeTap()
        assertTrue(viewModel.uiState.value.modal!!.confirmingFreeze)
        viewModel.onFreezeTap()
        awaitState { it.modal == null && it.frozenWallets.isNotEmpty() }

        assertTrue(viewModel.uiState.value.wallets.first { it.id == 1 }.frozen)
        assertEquals(listOf("To Freeze"), viewModel.uiState.value.frozenWallets.map { it.name })
        assertTrue(calls.toList().any { it.method == "DELETE" && it.path == "/api/wallets/1" })
    }

    @Test
    fun `unfreeze restores the wallet to its type section with one tap`() = runBlocking {
        seed(wallet(1, "Drawer", WalletType.CASH, "0.00", frozen = true))
        createViewModel()
        awaitLoaded()
        assertEquals(listOf("Drawer"), viewModel.uiState.value.frozenWallets.map { it.name })

        viewModel.unfreeze(viewModel.uiState.value.frozenWallets.first())
        awaitState { it.frozenWallets.isEmpty() && it.wallets.any { wallet -> wallet.id == 1 && !wallet.frozen } }

        assertTrue(calls.toList().any { it.method == "POST" && it.path == "/api/wallets/1/unfreeze" })
        assertEquals(
            "Drawer",
            viewModel.uiState.value.sections.first { it.type == WalletType.CASH }.items.single().name,
        )
    }

    // --- Load error + retry ---

    @Test
    fun `a load failure shows the error and retry refetches`() = runBlocking {
        listStatus = 500
        createViewModel()
        awaitLoaded()
        assertEquals("Could not load your wallets.", viewModel.uiState.value.loadError)

        listStatus = 200
        seed(wallet(1, "Cash", WalletType.CASH, "0.00"))
        viewModel.retry()
        awaitState { !it.loading && it.wallets.isNotEmpty() }

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("Cash", viewModel.uiState.value.wallets.first().name)
    }
}
