package com.budjetame.android.ui.transactions

import androidx.lifecycle.viewModelScope
import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.DraftTransactionApi
import com.budjetame.android.data.api.DraftTransactionDto
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringIncomeApi
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionCreateRequest
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.ApiCategoryRepository
import com.budjetame.android.data.drafttransaction.ApiDraftTransactionRepository
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.ApiRecurringCostRepository
import com.budjetame.android.data.recurringincome.ApiRecurringIncomeRepository
import com.budjetame.android.data.transaction.ApiTransactionRepository
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.wallet.ApiWalletRepository
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The pinned Draft Transactions on the Transactions tab (ADR-0036 in the web
 * repo, ticket #67), tested at the HTTP seam like the ledger itself: the
 * TransactionsViewModel is driven through the real repositories, Retrofit,
 * OkHttp and a MockWebServer whose dispatcher fakes /draft-transactions
 * (list, Approve with an optional full create body, Discard) next to the
 * ledger resources the rows and the form render from. The fake Approve runs
 * the backend's contract just far enough for the seam: the Draft's fields,
 * or the body in their place, must name a type, an amount, a date and the
 * type's Wallet(s), none frozen — otherwise 422 with the rule as `detail`
 * and the Draft left in place; a Draft that is gone answers 403.
 */
class DraftTransactionsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: TransactionsViewModel

    private val transactionStore = mutableListOf<TransactionDto>()
    private val walletStore = mutableListOf<WalletDto>()
    private val draftStore = mutableListOf<DraftTransactionDto>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var draftsStatus = 200
    private var discardStatus = 204
    private var approveWarning = false

    private val json = Json { ignoreUnknownKeys = true }

    private class NoLocation : DeviceLocation {
        override fun permissionGranted(): Boolean = false
        override suspend fun currentPosition(): LatLng? = null
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The dispatcher goes in before any ViewModel exists: its init
        // fetches at once, and an empty MockWebServer queue would hang it.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
    }

    @After
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        server.shutdown()
    }

    private fun createViewModel() {
        val client = ApiClient(server.url("/api/").toString()) { null }
        viewModel = TransactionsViewModel(
            transactions = ApiTransactionRepository(client.create(TransactionApi::class.java)),
            wallets = ApiWalletRepository(client.create(WalletApi::class.java)),
            categories = ApiCategoryRepository(client.create(CategoryApi::class.java)),
            recurringCosts = ApiRecurringCostRepository(client.create(RecurringCostApi::class.java)),
            recurringIncomes = ApiRecurringIncomeRepository(client.create(RecurringIncomeApi::class.java)),
            drafts = ApiDraftTransactionRepository(client.create(DraftTransactionApi::class.java)),
            searchDebounceMillis = 0,
            location = NoLocation(),
        )
    }

    // --- The fake backend ---

    @Synchronized
    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))
        val draftPath = Regex("/api/draft-transactions/(\\d+)")
        val approvePath = Regex("/api/draft-transactions/(\\d+)/approve")
        return when {
            method == "GET" && path == "/api/wallets" -> jsonResponse(200, json.encodeToString(walletStore.toList()))
            method == "GET" && path == "/api/categories" -> jsonResponse(200, "[]")
            method == "GET" && path == "/api/recurring-costs" -> jsonResponse(200, "[]")
            method == "GET" && path == "/api/recurring-incomes" -> jsonResponse(200, "[]")
            method == "GET" && path == "/api/transactions" -> jsonResponse(
                200,
                json.encodeToString(TransactionPageDto(transactionStore.sortedByDescending { it.id }, null)),
            )
            method == "GET" && path == "/api/draft-transactions" -> when {
                draftsStatus != 200 -> jsonResponse(draftsStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(draftStore.sortedBy { it.id }))
            }
            method == "POST" && approvePath.matches(path) ->
                approve(approvePath.find(path)!!.groupValues[1].toInt(), body)
            method == "DELETE" && draftPath.matches(path) -> {
                if (discardStatus != 204) return jsonResponse(discardStatus, """{"detail":"boom"}""")
                val id = draftPath.find(path)!!.groupValues[1].toInt()
                if (draftStore.removeIf { it.id == id }) {
                    MockResponse().setResponseCode(204)
                } else {
                    jsonResponse(403, """{"detail":"Draft transaction not found"}""")
                }
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** The fake Approve (ADR-0036): the body, when present, replaces the
     * Draft's fields; the create rules run; success creates the Transaction
     * and removes the Draft in the same step. */
    private fun approve(id: Int, body: String): MockResponse {
        val draft = draftStore.find { it.id == id }
            ?: return jsonResponse(403, """{"detail":"Draft transaction not found"}""")
        val fields = if (body.isBlank()) {
            draft
        } else {
            val create = json.decodeFromString<TransactionCreateRequest>(body)
            DraftTransactionDto(
                id = id,
                type = create.type,
                amount = create.amount,
                date = create.date,
                wallet_id = create.wallet_id,
                source_wallet_id = create.source_wallet_id,
                destination_wallet_id = create.destination_wallet_id,
                category_id = create.category_id,
                description = create.description,
                created_at = draft.created_at,
            )
        }
        ruleError(fields)?.let { return jsonResponse(422, """{"detail":"$it"}""") }
        val created = TransactionDto(
            id = (transactionStore.maxOfOrNull { it.id } ?: 0) + 1,
            type = fields.type!!,
            amount = fields.amount!!,
            date = fields.date!!,
            wallet_id = fields.wallet_id,
            source_wallet_id = fields.source_wallet_id,
            destination_wallet_id = fields.destination_wallet_id,
            category_id = fields.category_id,
            description = fields.description,
            warning = approveWarning,
            created_at = "2026-11-19T10:00:00Z",
        )
        transactionStore.add(created)
        draftStore.removeIf { it.id == id }
        return jsonResponse(201, json.encodeToString(created))
    }

    private fun ruleError(fields: DraftTransactionDto): String? {
        if (fields.type == null) return "Type is required"
        if (fields.amount == null) return "Amount is required"
        if (fields.date == null) return "Date is required"
        val legs = if (fields.type == TransactionType.TRANSFER) {
            listOf(fields.source_wallet_id, fields.destination_wallet_id)
        } else {
            listOf(fields.wallet_id)
        }
        if (legs.any { it == null }) return "Wallet is required"
        if (legs.any { leg -> walletStore.find { it.id == leg }?.frozen != false }) {
            return "Frozen Wallets are read-only"
        }
        return null
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    // --- Fixtures and waits ---

    private fun wallet(id: Int, name: String, frozen: Boolean = false) =
        WalletDto(id, name, WalletType.CHECKING, "100.00", frozen, "2026-08-01T10:00:00Z")

    private fun draft(
        id: Int,
        type: TransactionType? = TransactionType.EXPENSE,
        amount: String? = "30.00",
        date: String? = "2026-11-18",
        walletId: Int? = 1,
        description: String? = "Esselunga",
    ) = DraftTransactionDto(
        id = id,
        type = type,
        amount = amount,
        date = date,
        wallet_id = walletId,
        description = description,
        created_at = "2026-11-19T09:00:00Z",
    )

    private suspend fun awaitState(predicate: (TransactionsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun callsTo(method: String, path: String): List<RecordedCall> =
        calls.toList().filter { it.method == method && it.path == path }

    // --- The pinned block ---

    @Test
    fun `drafts written elsewhere appear above the ledger and never as ledger rows`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7))
        createViewModel()

        awaitState { !it.loading && it.drafts.isNotEmpty() }

        val state = viewModel.uiState.value
        assertEquals(listOf(7), state.drafts.map { it.id })
        assertEquals("Esselunga", state.drafts.single().description)
        // A Draft is not a Transaction: the ledger stays empty, and loading
        // the tab wrote nothing.
        assertTrue(state.transactions.isEmpty())
        assertTrue(calls.none { it.method != "GET" })
    }

    @Test
    fun `no drafts means an empty block`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        createViewModel()

        withTimeout(5_000) {
            while (callsTo("GET", "/api/draft-transactions").isEmpty()) delay(10)
        }
        awaitState { !it.loading }

        assertTrue(viewModel.uiState.value.drafts.isEmpty())
    }

    @Test
    fun `a draft written while the tab is open appears on the next data version bump`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        createViewModel()
        awaitState { !it.loading }
        withTimeout(5_000) {
            while (callsTo("GET", "/api/draft-transactions").isEmpty()) delay(10)
        }

        draftStore.add(draft(3))
        DataVersion.bump()

        awaitState { state -> state.drafts.map { it.id } == listOf(3) }
    }

    // --- Approve ---

    @Test
    fun `approve on a complete draft creates the Transaction and removes the row in one request`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7))
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }

        viewModel.approveDraft(viewModel.uiState.value.drafts.single())

        awaitState { it.drafts.isEmpty() && it.transactions.size == 1 }
        val approves = callsTo("POST", "/api/draft-transactions/7/approve")
        assertEquals(1, approves.size)
        // The one tap sends no body: the Draft is judged as it stands.
        assertEquals("", approves.single().body)
        // Approve is the one write: no separate create, no delete.
        assertTrue(callsTo("POST", "/api/transactions").isEmpty())
        assertTrue(calls.none { it.method == "DELETE" })
        val created = viewModel.uiState.value.transactions.single()
        assertEquals("30.00", created.amount)
        assertEquals(1, created.wallet_id)
        assertNull(viewModel.uiState.value.modal)
        assertNull(viewModel.uiState.value.draftError)
    }

    @Test
    fun `an approve that makes a Cash wallet negative shows the write warning`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7))
        approveWarning = true
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }

        viewModel.approveDraft(viewModel.uiState.value.drafts.single())

        awaitState { it.savedWarning != null }
        assertEquals("Saved — this made a Cash wallet negative.", viewModel.uiState.value.savedWarning)
    }

    @Test
    fun `approve on an incomplete draft opens the form pre-filled with the rule message`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7, walletId = null, description = "Esselunga"))
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }

        viewModel.approveDraft(viewModel.uiState.value.drafts.single())

        awaitState { it.modal != null }
        val modal = viewModel.uiState.value.modal!!
        assertEquals(7, modal.approving?.id)
        // A Draft is not yet a Transaction: the form is the create form,
        // its type and Wallets still choosable.
        assertNull(modal.editing)
        assertEquals(TransactionType.EXPENSE, modal.type)
        assertEquals("30.00", modal.amount)
        assertEquals("2026-11-18", modal.date)
        // The Wallet the Draft did not catch falls back to the new form's
        // default (the web's seed rule); the banner names the rule.
        assertEquals(1, modal.walletId)
        assertEquals("Esselunga", modal.description)
        assertEquals("Wallet is required", modal.error)
        // The Draft stays until the form's Save approves it.
        assertEquals(listOf(7), viewModel.uiState.value.drafts.map { it.id })
        assertTrue(viewModel.uiState.value.transactions.isEmpty())
    }

    @Test
    fun `the pre-filled form's save completes the approval with the edited fields`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7, walletId = null))
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }
        viewModel.approveDraft(viewModel.uiState.value.drafts.single())
        awaitState { it.modal != null }

        viewModel.onWalletChange(1)
        viewModel.onAmountChange("35.00")
        viewModel.submit()

        awaitState { it.modal == null && it.drafts.isEmpty() && it.transactions.size == 1 }
        val approves = callsTo("POST", "/api/draft-transactions/7/approve")
        assertEquals(2, approves.size)
        val body = json.parseToJsonElement(approves.last().body).jsonObject
        assertEquals("expense", body["type"].toString().trim('"'))
        assertEquals("35.00", body["amount"].toString().trim('"'))
        assertEquals("1", body["wallet_id"].toString())
        // The form's Save is Approve, never a plain create.
        assertTrue(callsTo("POST", "/api/transactions").isEmpty())
        assertEquals("35.00", viewModel.uiState.value.transactions.single().amount)
    }

    @Test
    fun `a field error from the form's approve keeps the form open and the draft in place`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        walletStore.add(wallet(2, "Old card", frozen = true))
        draftStore.add(draft(7, walletId = null))
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }
        viewModel.editDraft(viewModel.uiState.value.drafts.single())

        viewModel.onWalletChange(2)
        viewModel.submit()

        awaitState { it.modal?.error != null && it.modal?.submitting == false }
        assertEquals("Frozen Wallets are read-only", viewModel.uiState.value.modal?.error)
        assertEquals(7, viewModel.uiState.value.modal?.approving?.id)
        assertEquals(listOf(7), viewModel.uiState.value.drafts.map { it.id })
        assertTrue(transactionStore.isEmpty())
    }

    @Test
    fun `edit opens the pre-filled form directly, with no request and no message`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(
            DraftTransactionDto(
                id = 4,
                type = TransactionType.TRANSFER,
                amount = "50.00",
                date = null,
                source_wallet_id = 1,
                created_at = "2026-11-19T09:00:00Z",
            ),
        )
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }
        // The Drafts answering says nothing about the load's other fetches:
        // they run concurrently and can reach the server later. Wait for the
        // whole set (ledger, wallets, categories, both Recurring lists,
        // Drafts) before counting, or a late one reads as a new request.
        withTimeout(5_000) {
            while (calls.size < 6) delay(10)
        }
        val before = calls.size

        viewModel.editDraft(viewModel.uiState.value.drafts.single())

        val modal = viewModel.uiState.value.modal
        assertNotNull(modal)
        assertEquals(4, modal!!.approving?.id)
        assertEquals(TransactionType.TRANSFER, modal.type)
        assertEquals("50.00", modal.amount)
        assertEquals(1, modal.sourceWalletId)
        // A missing date stays empty, never silently today: Save waits for it.
        assertEquals("", modal.date)
        assertFalse(modal.canSubmit)
        assertNull(modal.error)
        assertEquals(before, calls.size)

        viewModel.onDateChange("2026-11-17")
        assertEquals("2026-11-17", viewModel.uiState.value.modal?.date)
    }

    @Test
    fun `a frozen or unknown wallet on the draft falls back to the form's default`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        walletStore.add(wallet(2, "Old card", frozen = true))
        draftStore.add(draft(5, walletId = 2))
        draftStore.add(draft(6, walletId = 99))
        createViewModel()
        awaitState { it.drafts.size == 2 }

        viewModel.editDraft(viewModel.uiState.value.drafts[0])
        assertEquals(1, viewModel.uiState.value.modal?.walletId)
        viewModel.closeModal()
        viewModel.editDraft(viewModel.uiState.value.drafts[1])
        assertEquals(1, viewModel.uiState.value.modal?.walletId)
    }

    @Test
    fun `a one-tap approve of a draft that is gone says so and drops the row`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7))
        createViewModel()
        awaitState { it.drafts.isNotEmpty() }
        // Approved from another device in the meantime.
        draftStore.clear()

        viewModel.approveDraft(viewModel.uiState.value.drafts.single())

        awaitState { it.draftError != null && it.drafts.isEmpty() }
        assertEquals("Could not approve the draft.", viewModel.uiState.value.draftError)
        assertNull(viewModel.uiState.value.modal)
    }

    // --- Discard ---

    @Test
    fun `discard removes the row at once and for good, with no undo`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7))
        draftStore.add(draft(8, description = "Pharmacy"))
        createViewModel()
        awaitState { it.drafts.size == 2 }

        viewModel.discardDraft(viewModel.uiState.value.drafts.first())

        // Gone from the block before the request even answers.
        assertEquals(listOf(8), viewModel.uiState.value.drafts.map { it.id })
        withTimeout(5_000) {
            while (callsTo("DELETE", "/api/draft-transactions/7").isEmpty()) delay(10)
        }
        // The write's bump reloads: the Draft does not come back, and
        // nothing was created.
        withTimeout(5_000) {
            while (callsTo("GET", "/api/draft-transactions").size < 2) delay(10)
        }
        awaitState { !it.loading }
        assertEquals(listOf(8), viewModel.uiState.value.drafts.map { it.id })
        assertTrue(viewModel.uiState.value.transactions.isEmpty())
        assertNull(viewModel.uiState.value.draftError)
    }

    @Test
    fun `a failed discard puts the row back where it was, with a message`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftStore.add(draft(7))
        draftStore.add(draft(8))
        discardStatus = 500
        createViewModel()
        awaitState { it.drafts.size == 2 }

        viewModel.discardDraft(viewModel.uiState.value.drafts.first())

        awaitState { it.draftError != null }
        assertEquals("Could not discard the draft.", viewModel.uiState.value.draftError)
        assertEquals(listOf(7, 8), viewModel.uiState.value.drafts.map { it.id })
    }

    @Test
    fun `a failed drafts fetch never takes down the ledger`() = runBlocking {
        walletStore.add(wallet(1, "Card"))
        draftsStatus = 500
        createViewModel()

        withTimeout(5_000) {
            while (callsTo("GET", "/api/draft-transactions").isEmpty()) delay(10)
        }
        awaitState { !it.loading }

        assertNull(viewModel.uiState.value.loadError)
        assertTrue(viewModel.uiState.value.drafts.isEmpty())
    }
}
