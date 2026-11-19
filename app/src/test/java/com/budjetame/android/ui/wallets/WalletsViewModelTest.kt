package com.budjetame.android.ui.wallets

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletCreateRequest
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.api.WalletUpdateRequest
import com.budjetame.android.data.wallet.ApiWalletRepository
import com.budjetame.android.ui.common.AmountInputMessages
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

            method == "POST" && path.matches(Regex("/api/wallets/\\d+/freeze")) -> {
                val id = path.removePrefix("/api/wallets/").removeSuffix("/freeze").toInt()
                val index = store.indexOfFirst { it.id == id }
                val frozen = store[index].copy(frozen = true)
                store[index] = frozen
                jsonResponse(200, json.encodeToString(frozen))
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

    // --- Submit-and-validate (ADR-0009) ---

    private fun postedCreates(): List<RecordedCall> =
        calls.toList().filter { it.method == "POST" && it.path == "/api/wallets" }

    @Test
    fun `save on an empty name reveals a name field error and creates nothing`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("   ")
        viewModel.submit()

        val modal = viewModel.uiState.value.modal!!
        assertEquals(mapOf(WalletFields.NAME to "Enter a name"), modal.fieldErrors)
        assertNull(modal.error)
        assertFalse(modal.submitting)
        assertTrue(postedCreates().isEmpty())
    }

    @Test
    fun `save on a cleared name while editing reveals the name error and renames nothing`() = runBlocking {
        seed(wallet(1, "Mine", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.wallets.first())
        viewModel.onNameChange("")
        viewModel.submit()

        assertEquals(
            mapOf(WalletFields.NAME to "Enter a name"),
            viewModel.uiState.value.modal?.fieldErrors,
        )
        assertTrue(calls.toList().none { it.method == "PATCH" })
    }

    @Test
    fun `the opening balance accepts a comma decimal and sends the canonical value`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Comma")
        viewModel.onTypeChange(WalletType.CHECKING)
        viewModel.onOpeningBalanceChange("1.000,5")
        viewModel.submit()
        awaitState { it.modal == null && it.wallets.any { wallet -> wallet.name == "Comma" } }

        val create = json.decodeFromString<WalletCreateRequest>(call("POST", "/api/wallets").body)
        assertEquals("1000.5", create.opening_balance)
    }

    @Test
    fun `a malformed opening balance reveals the amount input error and creates nothing`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Typo")
        viewModel.onTypeChange(WalletType.CASH)
        viewModel.onOpeningBalanceChange("12abc")
        viewModel.submit()

        assertEquals(
            mapOf(WalletFields.OPENING_BALANCE to AmountInputMessages.INVALID),
            viewModel.uiState.value.modal?.fieldErrors,
        )
        assertTrue(postedCreates().isEmpty())
    }

    @Test
    fun `a negative opening balance reveals the not-positive error and creates nothing`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Negative")
        viewModel.onTypeChange(WalletType.CASH)
        viewModel.onOpeningBalanceChange("-1.00")
        viewModel.submit()

        assertEquals(
            mapOf(WalletFields.OPENING_BALANCE to AmountInputMessages.NOT_POSITIVE),
            viewModel.uiState.value.modal?.fieldErrors,
        )
        assertNull(viewModel.uiState.value.modal?.error)
        assertTrue(postedCreates().isEmpty())
    }

    @Test
    fun `every wrong field reveals its error at once`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onOpeningBalanceChange("abc")
        viewModel.submit()

        assertEquals(
            mapOf(
                WalletFields.NAME to "Enter a name",
                WalletFields.OPENING_BALANCE to AmountInputMessages.INVALID,
            ),
            viewModel.uiState.value.modal?.fieldErrors,
        )
        assertTrue(postedCreates().isEmpty())
    }

    @Test
    fun `field errors persist while typing and the next valid save clears them and creates`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.submit()
        viewModel.onNameChange("Fixed")
        assertEquals(
            mapOf(WalletFields.NAME to "Enter a name"),
            viewModel.uiState.value.modal?.fieldErrors,
        )

        viewModel.submit()
        assertTrue(viewModel.uiState.value.modal?.fieldErrors.orEmpty().isEmpty())
        awaitState { it.modal == null && it.wallets.any { wallet -> wallet.name == "Fixed" } }
        assertEquals(1, postedCreates().size)
    }

    @Test
    fun `a contact wallet skips the opening balance and starts at zero`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Anna")
        viewModel.onTypeChange(WalletType.CONTACT)
        viewModel.submit()
        awaitState { it.modal == null && it.wallets.any { wallet -> wallet.name == "Anna" } }

        val create = json.decodeFromString<WalletCreateRequest>(call("POST", "/api/wallets").body)
        assertEquals(WalletType.CONTACT, create.type)
        assertEquals("0.00", create.opening_balance)
    }

    @Test
    fun `save is disabled only by in-flight work never by validation`() {
        createViewModel()
        viewModel.openCreate()

        val empty = viewModel.uiState.value.modal!!
        assertFalse(empty.busy)
        assertTrue(empty.copy(submitting = true).busy)
        assertTrue(empty.copy(freezing = true).busy)
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
        assertTrue(viewModel.uiState.value.modal?.fieldErrors.orEmpty().isEmpty())
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
        assertTrue(calls.toList().any { it.method == "POST" && it.path == "/api/wallets/1/freeze" })
        assertFalse(calls.toList().any { it.method == "DELETE" })
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
