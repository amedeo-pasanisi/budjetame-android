package com.budjetame.android.ui.transactions

import androidx.lifecycle.viewModelScope
import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.ApiCategoryRepository
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.transaction.ApiTransactionRepository
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.ApiWalletRepository
import com.budjetame.android.data.wallet.WalletGateway
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The Transactions ledger tested at the single seam (the HTTP API): the
 * ViewModel is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of the
 * /transactions listing — newest-first keyset paging over the same filter
 * set the backend applies (wallet on either leg of a Transfer, category,
 * inclusive date range, case-insensitive literal q) — plus the /wallets and
 * /categories resources the rows and filter bar render from. Requests are
 * captured for assertions (the cursor must be handed back verbatim).
 */
class TransactionsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(
        val method: String,
        val path: String,
        val query: Map<String, String>,
    )

    private lateinit var server: MockWebServer
    private lateinit var viewModel: TransactionsViewModel

    private val transactionStore = mutableListOf<TransactionDto>()
    private val walletStore = mutableListOf<WalletDto>()
    private val categoryStore = mutableListOf<CategoryDto>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var listStatus = 200
    private var loadMoreStatus = 200
    private var walletsStatus = 200
    private var categoriesStatus = 200

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transactionStore.clear()
        walletStore.clear()
        categoryStore.clear()
        calls.clear()
        listStatus = 200
        loadMoreStatus = 200
        walletsStatus = 200
        categoriesStatus = 200
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = route(request)
        }
    }

    @After
    fun tearDown() {
        // Stop the ViewModel's DataVersion collector before the server goes
        // down: a leftover collector would wake on later tests' version bumps
        // and refetch against a dead server (cross-test contamination).
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        server.shutdown()
    }

    private fun createViewModel(searchDebounceMillis: Long = 0) {
        val client = ApiClient(server.url("/api/").toString()) { null }
        val transactions = ApiTransactionRepository(client.create(TransactionApi::class.java))
        val wallets = ApiWalletRepository(client.create(WalletApi::class.java))
        val categories = ApiCategoryRepository(client.create(CategoryApi::class.java))
        viewModel = TransactionsViewModel(
            transactions = transactions,
            wallets = wallets,
            categories = categories,
            searchDebounceMillis = searchDebounceMillis,
        )
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val url = request.requestUrl
        val query = url?.queryParameterNames.orEmpty()
            .associateWith { name -> url!!.queryParameter(name) ?: "" }
        calls.add(RecordedCall(method, path, query))

        return when {
            method == "GET" && path == "/api/wallets" -> when {
                walletsStatus != 200 -> jsonResponse(walletsStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(walletStore))
            }

            method == "GET" && path == "/api/categories" -> when {
                categoriesStatus != 200 -> jsonResponse(categoriesStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(categoryStore))
            }

            method == "GET" && path == "/api/transactions" -> listPage(query)
            else -> MockResponse().setResponseCode(404)
        }
    }

    /** The fake ledger listing, mirroring the backend's contract: filters
     * compose, rows come back newest-first ((date, id) descending), and the
     * opaque `cursor-N` token names the page boundary the fake slices from. */
    private fun listPage(query: Map<String, String>): MockResponse {
        val isMorePage = query.containsKey("cursor")
        val status = if (isMorePage) loadMoreStatus else listStatus
        if (status != 200) return jsonResponse(status, """{"detail":"boom"}""")

        val limit = (query["limit"] ?: "50").toInt()
        var filtered: List<TransactionDto> = transactionStore
        query["wallet_id"]?.let { id ->
            val walletId = id.toInt()
            filtered = filtered.filter {
                it.wallet_id == walletId ||
                    it.source_wallet_id == walletId ||
                    it.destination_wallet_id == walletId
            }
        }
        query["category_id"]?.let { id -> filtered = filtered.filter { it.category_id == id.toInt() } }
        query["from_date"]?.let { bound -> filtered = filtered.filter { it.date >= bound } }
        query["to_date"]?.let { bound -> filtered = filtered.filter { it.date <= bound } }
        query["q"]?.let { needle ->
            filtered = filtered.filter { it.description?.contains(needle, ignoreCase = true) == true }
        }
        val sorted = filtered.sortedWith(
            compareByDescending<TransactionDto> { it.date }.thenByDescending { it.id },
        )
        val start = query["cursor"]?.removePrefix("cursor-")?.toIntOrNull() ?: 0
        val page = sorted.drop(start).take(limit)
        val nextStart = start + page.size
        val nextCursor = if (nextStart < sorted.size) "cursor-$nextStart" else null
        return jsonResponse(200, json.encodeToString(TransactionPageDto(page, nextCursor)))
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun seedTransactions(vararg transactions: TransactionDto) {
        transactionStore.addAll(transactions)
    }

    private fun seedWallets(vararg wallets: WalletDto) {
        walletStore.addAll(wallets)
    }

    private fun seedCategories(vararg categories: CategoryDto) {
        categoryStore.addAll(categories)
    }

    private fun transaction(
        id: Int,
        type: TransactionType,
        amount: String,
        date: String,
        walletId: Int? = null,
        sourceWalletId: Int? = null,
        destinationWalletId: Int? = null,
        categoryId: Int? = null,
        description: String? = null,
    ) = TransactionDto(
        id = id,
        type = type,
        amount = amount,
        date = date,
        wallet_id = walletId,
        source_wallet_id = sourceWalletId,
        destination_wallet_id = destinationWalletId,
        category_id = categoryId,
        description = description,
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun wallet(id: Int, name: String, type: WalletType, balance: String, frozen: Boolean = false) =
        WalletDto(id, name, type, balance, frozen, "2026-08-01T10:00:00Z")

    private fun category(id: Int, name: String, type: CategoryType, icon: String? = null) =
        CategoryDto(id, name, type, icon, "#000000", "2026-08-01T10:00:00Z")

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (TransactionsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun listCalls(): List<RecordedCall> =
        calls.toList().filter { it.path == "/api/transactions" }

    // --- Initial load: newest first, with the wallets and categories ---

    @Test
    fun `the ledger loads newest-first with the wallets and categories the rows render from`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedCategories(category(1, "Food", CategoryType.EXPENSE, icon = "🍕"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-03", walletId = 1, categoryId = 1, description = "Old"),
            transaction(2, TransactionType.EXPENSE, "7.50", "2026-08-05", walletId = 1, description = "New"),
        )
        createViewModel()
        awaitLoaded()

        val state = viewModel.uiState.value
        assertEquals(listOf(2, 1), state.transactions.map { it.id })
        assertNull(state.nextCursor)
        assertEquals(listOf("Cash"), state.wallets.map { it.name })
        assertEquals(listOf("Food"), state.categories.map { it.name })
        // The unfiltered, unsearched fetch is the truth about the ledger.
        assertFalse(state.ledgerEmpty)

        val first = listCalls().first()
        assertEquals("50", first.query["limit"])
        assertNull(first.query["cursor"])
    }

    // --- Cursor paging ---

    @Test
    fun `paging hands the opaque cursor back verbatim and appends without duplicates`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedTransactions(
            *(1..55).map { transaction(it, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1) }.toTypedArray(),
        )
        createViewModel()
        awaitLoaded()

        val firstPage = viewModel.uiState.value
        assertEquals(50, firstPage.transactions.size)
        assertNotNull(firstPage.nextCursor)
        val cursor = firstPage.nextCursor ?: error("expected a next cursor")

        viewModel.loadMore()
        awaitState { it.transactions.size == 55 }

        val state = viewModel.uiState.value
        // Every row exactly once across the two pages.
        assertEquals(55, state.transactions.map { it.id }.toSet().size)
        assertNull(state.nextCursor)

        val calls = listCalls()
        assertEquals(2, calls.size)
        assertEquals("50", calls[0].query["limit"])
        assertNull(calls[0].query["cursor"])
        assertEquals("50", calls[1].query["limit"])
        // The opaque token from the first page travels back unchanged.
        assertEquals(cursor, calls[1].query["cursor"])

        // The last page ends paging: nothing further is fetched.
        val callCount = listCalls().size
        viewModel.loadMore()
        assertEquals(callCount, listCalls().size)
    }

    // --- Filters ---

    @Test
    fun `filters compose into one first-page refetch carrying every field`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedCategories(category(1, "Food", CategoryType.EXPENSE), category(2, "Fuel", CategoryType.EXPENSE))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1, categoryId = 1, description = "Lunch"),
            transaction(2, TransactionType.EXPENSE, "7.00", "2026-08-02", walletId = 1, categoryId = 1, description = "Dinner"),
            transaction(3, TransactionType.EXPENSE, "9.00", "2026-08-02", walletId = 1, categoryId = 2, description = "Gas"),
            transaction(4, TransactionType.EXPENSE, "11.00", "2026-08-03", walletId = 2, categoryId = 1, description = "Trip"),
        )
        createViewModel()
        awaitLoaded()
        assertEquals(listOf(4, 3, 2, 1), viewModel.uiState.value.transactions.map { it.id })

        viewModel.onFilterWalletChange(1)
        viewModel.onFilterCategoryChange(1)
        viewModel.onFilterFromDateChange("2026-08-02")
        viewModel.onFilterToDateChange("2026-08-02")
        // Only the full filter set yields this row: wallet 1 ∧ category 1 ∧
        // exactly 2026-08-02 — every partial combination matches more rows.
        awaitState { it.transactions.map { t -> t.id } == listOf(2) }

        val last = listCalls().last()
        assertEquals("1", last.query["wallet_id"])
        assertEquals("1", last.query["category_id"])
        assertEquals("2026-08-02", last.query["from_date"])
        assertEquals("2026-08-02", last.query["to_date"])
        assertNull(last.query["cursor"])
        assertTrue(viewModel.uiState.value.filtersActive)
        assertEquals("No transactions match these filters.", viewModel.uiState.value.emptyMessage)
    }

    @Test
    fun `a filter change resets to the first page without a cursor`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedTransactions(
            *(1..55).map { transaction(it, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1) }.toTypedArray(),
        )
        createViewModel()
        awaitLoaded()
        assertNotNull(viewModel.uiState.value.nextCursor)

        viewModel.loadMore()
        awaitState { it.transactions.size == 55 }
        assertNull(viewModel.uiState.value.nextCursor)

        // Wallet 2 has no rows: the refetch resets to a filtered empty first page.
        viewModel.onFilterWalletChange(2)
        awaitState { it.transactions.isEmpty() }

        val last = listCalls().last()
        assertEquals("2", last.query["wallet_id"])
        assertNull(last.query["cursor"])
        assertNull(viewModel.uiState.value.nextCursor)
    }

    @Test
    fun `reselecting the same filter value does not refetch`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.onFilterWalletChange(1)
        withTimeout(5_000) {
            while (listCalls().size < 2) delay(10)
        }
        // The same selection again is a no-op, like the web's React bail-out.
        viewModel.onFilterWalletChange(1)
        delay(100)
        assertEquals(2, listCalls().size)

        // A real change refetches.
        viewModel.onFilterWalletChange(null)
        withTimeout(5_000) {
            while (listCalls().size < 3) delay(10)
        }
    }

    // --- Search ---

    @Test
    fun `search trims the needle composes with the filter bar and clearing restores the filtered list`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1, description = "Coffee"),
            transaction(2, TransactionType.EXPENSE, "6.00", "2026-08-02", walletId = 2, description = "Coffee at the bar"),
            transaction(3, TransactionType.EXPENSE, "7.00", "2026-08-02", walletId = 2, description = "Lunch"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onFilterWalletChange(2)
        awaitState { it.transactions.size == 2 && it.transactions.all { t -> t.wallet_id == 2 } }

        viewModel.onSearchChange("  coffee ")
        assertEquals("  coffee ", viewModel.uiState.value.search)
        awaitState { it.searchNeedle == "coffee" && it.transactions.size == 1 }

        var last = listCalls().last()
        assertEquals("2", last.query["wallet_id"])
        assertEquals("coffee", last.query["q"])
        assertEquals(listOf(2), viewModel.uiState.value.transactions.map { it.id })
        assertEquals("No transactions match your search.", viewModel.uiState.value.emptyMessage)

        // Clearing the search restores the filtered list, not the whole ledger.
        viewModel.onSearchChange("")
        awaitState { it.searchNeedle.isEmpty() && it.transactions.size == 2 }

        last = listCalls().last()
        assertEquals("2", last.query["wallet_id"])
        assertNull(last.query["q"])
        assertEquals(listOf(3, 2), viewModel.uiState.value.transactions.map { it.id })
    }

    @Test
    fun `a whitespace-only search means no filter`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1, description = "Lunch"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onSearchChange("lunch")
        awaitState { it.searchNeedle == "lunch" }
        viewModel.onSearchChange("   ")
        // The refetch lands but may conflate (same list content); await the
        // request instead of a state change.
        withTimeout(5_000) {
            while (listCalls().size < 3) delay(10)
        }

        assertEquals("", viewModel.uiState.value.searchNeedle)
        assertNull(listCalls().last().query["q"])
        assertEquals(listOf(1), viewModel.uiState.value.transactions.map { it.id })
    }

    @Test
    fun `the search needle is debounced across rapid typing into one refetch`() {
        val gateway = RecordingGateway()
        viewModel = TransactionsViewModel(gateway, gateway, gateway)
        mainRule.dispatcher.scheduler.runCurrent()
        val callsBefore = gateway.transactionCalls

        viewModel.onSearchChange("caf")
        viewModel.onSearchChange("caffe")
        // Two keystrokes inside one debounce window: nothing refetched yet.
        assertEquals(callsBefore, gateway.transactionCalls)

        mainRule.dispatcher.scheduler.advanceTimeBy(TransactionsViewModel.SEARCH_DEBOUNCE_MILLIS)
        mainRule.dispatcher.scheduler.runCurrent()

        assertEquals(callsBefore + 1, gateway.transactionCalls)
        assertEquals("caffe", gateway.lastFilters.q)
        assertEquals("caffe", viewModel.uiState.value.searchNeedle)
    }

    // --- Empty ledger ---

    @Test
    fun `the truly empty ledger is told apart from a filtered miss`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()
        assertTrue(viewModel.uiState.value.ledgerEmpty)
        assertEquals("Nothing here yet.", viewModel.uiState.value.emptyMessage)
        assertFalse(viewModel.uiState.value.filtersActive)

        viewModel.onFilterWalletChange(1)
        withTimeout(5_000) {
            while (listCalls().size < 2) delay(10)
        }
        // The ledger-empty truth comes only from unfiltered fetches: the
        // filtered miss stays a filter miss (search bar stays hidden either
        // way until an unfiltered fetch says otherwise).
        assertTrue(viewModel.uiState.value.ledgerEmpty)
        assertEquals("No transactions match these filters.", viewModel.uiState.value.emptyMessage)
        assertTrue(viewModel.uiState.value.filtersActive)
    }

    // --- Errors ---

    @Test
    fun `a load failure shows the error and retry refetches`() = runBlocking {
        listStatus = 500
        createViewModel()
        awaitLoaded()
        assertEquals("Could not load your data.", viewModel.uiState.value.loadError)

        listStatus = 200
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1))
        viewModel.retry()
        awaitState { !it.loading && it.transactions.isNotEmpty() }

        assertNull(viewModel.uiState.value.loadError)
        assertEquals(listOf(1), viewModel.uiState.value.transactions.map { it.id })
    }

    @Test
    fun `a failed load-more shows its error never auto-retries and retry resumes paging`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedTransactions(
            *(1..55).map { transaction(it, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1) }.toTypedArray(),
        )
        createViewModel()
        awaitLoaded()
        assertEquals(50, viewModel.uiState.value.transactions.size)

        loadMoreStatus = 500
        viewModel.loadMore()
        awaitState { it.loadMoreError != null }

        assertEquals("Could not load more transactions.", viewModel.uiState.value.loadMoreError)
        assertEquals(50, viewModel.uiState.value.transactions.size)

        // A failed load-more never auto-retries from the same trigger.
        val callCount = listCalls().size
        viewModel.loadMore()
        assertEquals(callCount, listCalls().size)

        loadMoreStatus = 200
        viewModel.retryLoadMore()
        awaitState { it.transactions.size == 55 }

        assertNull(viewModel.uiState.value.loadMoreError)
        assertNull(viewModel.uiState.value.nextCursor)
    }

    @Test
    fun `a failed background refetch keeps the held list on screen`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1))
        createViewModel()
        awaitLoaded()

        listStatus = 500
        DataVersion.bump()
        withTimeout(5_000) {
            while (listCalls().size < 2) delay(10)
        }

        // ADR-0002: the held data stays on screen; no error replaces it.
        assertEquals(listOf(1), viewModel.uiState.value.transactions.map { it.id })
        assertNull(viewModel.uiState.value.loadError)
        assertFalse(viewModel.uiState.value.loading)
    }

    /** A stub triple gateway for the pure-timing debounce test: the seam
     * tests drive the real repositories; this one only counts fetches. */
    private class RecordingGateway : TransactionGateway, WalletGateway, CategoryGateway {
        var transactionCalls = 0
            private set
        var lastFilters = TransactionFilters()
            private set

        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto {
            transactionCalls++
            lastFilters = filters
            return TransactionPageDto(emptyList(), null)
        }

        override suspend fun fetchWallets(): List<WalletDto> = emptyList()

        override suspend fun fetchCategories(): List<CategoryDto> = emptyList()

        override suspend fun createWallet(name: String, type: WalletType, openingBalance: String): WalletDto =
            error("unused in the debounce test")

        override suspend fun renameWallet(id: Int, name: String): WalletDto =
            error("unused in the debounce test")

        override suspend fun freezeWallet(id: Int) = error("unused in the debounce test")

        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused in the debounce test")

        override suspend fun createCategory(name: String, type: CategoryType, icon: String, color: String): CategoryDto =
            error("unused in the debounce test")

        override suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto =
            error("unused in the debounce test")

        override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto =
            error("unused in the debounce test")

        override suspend fun deleteCategory(id: Int) = error("unused in the debounce test")
    }
}
