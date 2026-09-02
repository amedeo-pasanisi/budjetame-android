package com.budjetame.android.ui.transactions

import androidx.lifecycle.viewModelScope
import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.CategoryCreateRequest
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionCreateRequest
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionExpenseIncomeUpdateRequest
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionTransferUpdateRequest
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletCreateRequest
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.ApiCategoryRepository
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.transaction.ApiTransactionRepository
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.ApiWalletRepository
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.util.Dates
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
 * /transactions resource — the newest-first keyset paging listing over the
 * same filter set the backend applies (wallet on either leg of a Transfer,
 * category, inclusive date range, case-insensitive literal q), plus the
 * create/PATCH/delete writes with the backend's type rules (ticket #20), and
 * the /wallets and /categories resources the rows and the form render from.
 * Requests are captured for assertions (the cursor must be handed back
 * verbatim; the write bodies must carry exactly the type's fields).
 */
class TransactionsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String = "",
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
    private var createStatus = 201
    private var walletCreateStatus = 201
    private var categoryCreateStatus = 201
    private var updateStatus = 200
    private var deleteStatus = 200
    private var createWarning = false
    private var updateWarning = false
    private var deleteWarning = false

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
        createStatus = 201
        walletCreateStatus = 201
        categoryCreateStatus = 201
        updateStatus = 200
        deleteStatus = 200
        createWarning = false
        updateWarning = false
        deleteWarning = false
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
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, query, body))

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

            method == "POST" && path == "/api/wallets" -> createWallet(body)

            method == "POST" && path == "/api/categories" -> createCategory(body)

            method == "POST" && path == "/api/transactions" -> createTransaction(body)

            method == "PATCH" && path.matches(Regex("/api/transactions/\\d+")) -> updateTransaction(path, body)

            method == "DELETE" && path.matches(Regex("/api/transactions/\\d+")) -> deleteTransaction(path)

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

    /** The fake inline Wallet create (ADR-0014): real at once — the Wallet
     * lands in the store the ledger and the fake validation read from. */
    private fun createWallet(body: String): MockResponse {
        if (walletCreateStatus != 201) return jsonResponse(walletCreateStatus, """{"detail":"boom"}""")
        val create = json.decodeFromString<WalletCreateRequest>(body)
        if (create.name.isBlank()) return jsonResponse(422, """{"detail":"Name is required"}""")
        if (walletStore.any { it.name.equals(create.name, ignoreCase = true) }) {
            return jsonResponse(409, """{"detail":"A Wallet with this name already exists"}""")
        }
        val id = (walletStore.maxOfOrNull { it.id } ?: 0) + 1
        val created = wallet(id, create.name, create.type, create.opening_balance)
        walletStore.add(created)
        return jsonResponse(201, json.encodeToString(created))
    }

    /** The fake inline Category create (ADR-0014): real at once — the
     * Category lands in the store the ledger and the fake validation read
     * from; a blank icon stores as null, like the backend. */
    private fun createCategory(body: String): MockResponse {
        if (categoryCreateStatus != 201) {
            return jsonResponse(categoryCreateStatus, """{"detail":"boom"}""")
        }
        val create = json.decodeFromString<CategoryCreateRequest>(body)
        if (create.name.isBlank()) return jsonResponse(422, """{"detail":"Name is required"}""")
        if (categoryStore.any {
                it.type == create.type && it.name.equals(create.name, ignoreCase = true)
            }
        ) {
            return jsonResponse(409, """{"detail":"A Category with this name already exists"}""")
        }
        val id = (categoryStore.maxOfOrNull { it.id } ?: 0) + 1
        val created = CategoryDto(
            id = id,
            name = create.name,
            type = create.type,
            icon = create.icon.ifBlank { null },
            color = create.color,
            created_at = "2026-08-01T10:00:00Z",
        )
        categoryStore.add(created)
        return jsonResponse(201, json.encodeToString(created))
    }

    /** The fake create, mirroring the backend's type rules just far enough
     * for the seam: the form itself gates the rest client-side. */
    private fun createTransaction(body: String): MockResponse {
        if (createStatus != 201) return jsonResponse(createStatus, """{"detail":"boom"}""")
        val create = json.decodeFromString<TransactionCreateRequest>(body)
        createRuleError(create)?.let { error ->
            return jsonResponse(422, """{"detail":"$error"}""")
        }
        val id = (transactionStore.maxOfOrNull { it.id } ?: 0) + 1
        val created = TransactionDto(
            id = id,
            type = create.type,
            amount = create.amount,
            date = create.date,
            wallet_id = create.wallet_id,
            source_wallet_id = create.source_wallet_id,
            destination_wallet_id = create.destination_wallet_id,
            category_id = create.category_id,
            description = create.description,
            warning = createWarning,
            created_at = "2026-08-01T10:00:00Z",
        )
        transactionStore.add(created)
        return jsonResponse(201, json.encodeToString(created))
    }

    private fun createRuleError(create: TransactionCreateRequest): String? {
        if (create.type == TransactionType.OPENING_BALANCE) {
            return "Type must be expense, income, or transfer"
        }
        if (create.type == TransactionType.TRANSFER) {
            if (create.wallet_id != null || create.category_id != null) {
                return "Transfers use source and destination Wallets and never carry a Category"
            }
            val source = create.source_wallet_id
            val destination = create.destination_wallet_id
            if (source == null || destination == null) {
                return "Transfers need source and destination Wallets"
            }
            if (source == destination) {
                return "Source and Destination must be different Wallets"
            }
            if (walletStore.find { it.id == source }?.frozen == true ||
                walletStore.find { it.id == destination }?.frozen == true
            ) {
                return "Frozen Wallets are read-only"
            }
            return null
        }
        val walletId = create.wallet_id ?: return "wallet_id is required for Expense and Income"
        if (create.source_wallet_id != null || create.destination_wallet_id != null) {
            return "source and destination Wallets are only for Transfers"
        }
        val wallet = walletStore.find { it.id == walletId } ?: return "Wallet not found"
        if (wallet.frozen) return "Frozen Wallets are read-only"
        if (wallet.type == WalletType.CONTACT && create.type != TransactionType.EXPENSE) {
            return "Incomes can't be recorded on Contact Wallets"
        }
        create.category_id?.let { categoryId ->
            val category = categoryStore.find { it.id == categoryId } ?: return "Category not found"
            val expected = if (create.type == TransactionType.EXPENSE) {
                CategoryType.EXPENSE
            } else {
                CategoryType.INCOME
            }
            if (category.type != expected) {
                return "A Category attaches only to Transactions of its Type"
            }
        }
        return null
    }

    /** The fake update, mirroring the PATCH contract: the right body shape per
     * type, frozen/Opening Balance rejected, and the warning flag echoed. */
    private fun updateTransaction(path: String, body: String): MockResponse {
        if (updateStatus != 200) return jsonResponse(updateStatus, """{"detail":"boom"}""")
        val id = path.removePrefix("/api/transactions/").toInt()
        val index = transactionStore.indexOfFirst { it.id == id }
        if (index < 0) return jsonResponse(403, """{"detail":"Transaction not found"}""")
        val current = transactionStore[index]
        if (current.type == TransactionType.OPENING_BALANCE) {
            return jsonResponse(422, """{"detail":"Opening Balance Transactions are read-only"}""")
        }
        if (isFrozen(current)) return jsonResponse(422, """{"detail":"Frozen Wallets are read-only"}""")

        val updated = if (current.type == TransactionType.TRANSFER) {
            val update = json.decodeFromString<TransactionTransferUpdateRequest>(body)
            current.copy(
                amount = update.amount,
                date = update.date,
                description = update.description,
                warning = updateWarning,
            )
        } else {
            val update = json.decodeFromString<TransactionExpenseIncomeUpdateRequest>(body)
            current.copy(
                amount = update.amount,
                date = update.date,
                category_id = update.category_id,
                description = update.description,
                warning = updateWarning,
            )
        }
        transactionStore[index] = updated
        return jsonResponse(200, json.encodeToString(updated))
    }

    private fun deleteTransaction(path: String): MockResponse {
        if (deleteStatus != 200) return jsonResponse(deleteStatus, """{"detail":"boom"}""")
        val id = path.removePrefix("/api/transactions/").toInt()
        val index = transactionStore.indexOfFirst { it.id == id }
        if (index < 0) return jsonResponse(403, """{"detail":"Transaction not found"}""")
        val current = transactionStore[index]
        if (current.type == TransactionType.OPENING_BALANCE) {
            return jsonResponse(422, """{"detail":"Opening Balance Transactions are read-only"}""")
        }
        if (isFrozen(current)) return jsonResponse(422, """{"detail":"Frozen Wallets are read-only"}""")
        transactionStore.removeAt(index)
        return jsonResponse(200, """{"warning":$deleteWarning}""")
    }

    private fun isFrozen(transaction: TransactionDto): Boolean =
        if (transaction.type == TransactionType.TRANSFER) {
            val source = walletStore.find { it.id == transaction.source_wallet_id }
            val destination = walletStore.find { it.id == transaction.destination_wallet_id }
            source?.frozen == true || destination?.frozen == true
        } else {
            walletStore.find { it.id == transaction.wallet_id }?.frozen == true
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

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

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

    // --- Transaction form write path (ticket #20) ---

    @Test
    fun `create an expense sends wallet and category and refetches via the data-version bump`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedCategories(category(1, "Food", CategoryType.EXPENSE))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.onDateChange("2026-08-05")
        viewModel.onWalletChange(1)
        viewModel.onCategoryChange(1)
        viewModel.onDescriptionChange("Lunch")
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.id == 1 } }

        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals(TransactionType.EXPENSE, create.type)
        assertEquals("5.00", create.amount)
        assertEquals("2026-08-05", create.date)
        assertEquals(1, create.wallet_id)
        assertEquals(1, create.category_id)
        assertNull(create.source_wallet_id)
        assertNull(create.destination_wallet_id)
        assertEquals("Lunch", create.description)
        assertNull(viewModel.uiState.value.savedWarning)
        // The transport bumped the data version after the write, so the
        // ledger refetched in the background (ADR-0002).
        assertTrue(listCalls().size >= 2)
    }

    @Test
    fun `create a transfer sends the legs and never wallet or category`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedCategories(category(1, "Food", CategoryType.EXPENSE))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.TRANSFER)
        viewModel.onAmountChange("50.00")
        viewModel.onSourceWalletChange(1)
        viewModel.onDestinationWalletChange(2)
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.type == TransactionType.TRANSFER } }

        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals(TransactionType.TRANSFER, create.type)
        assertEquals(1, create.source_wallet_id)
        assertEquals(2, create.destination_wallet_id)
        assertNull(create.wallet_id)
        assertNull(create.category_id)
    }

    @Test
    fun `a transfer with the same source and destination never submits`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.TRANSFER)
        viewModel.onAmountChange("10.00")
        viewModel.onSourceWalletChange(1)
        viewModel.onDestinationWalletChange(1)
        viewModel.submit()

        assertFalse(viewModel.uiState.value.modal!!.canSubmit)
        assertTrue(calls.toList().none { it.method == "POST" && it.path == "/api/transactions" })
    }

    @Test
    fun `switching an expense that picked a contact wallet to income resets the wallet`() = runBlocking {
        seedWallets(
            wallet(1, "Marco", WalletType.CONTACT, "0.00"),
            wallet(2, "Cash", WalletType.CASH, "100.00"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onWalletChange(1)
        viewModel.onTypeChange(TransactionType.INCOME)

        assertEquals(2, viewModel.uiState.value.modal?.walletId)
    }

    @Test
    fun `the create draft defaults the date to today in Europe Rome and the first spendable wallet`() = runBlocking {
        seedWallets(
            wallet(1, "Marco", WalletType.CONTACT, "0.00"),
            wallet(2, "Cash", WalletType.CASH, "100.00"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()

        val modal = viewModel.uiState.value.modal
        assertEquals(Dates.toApiDay(Dates.todayInRome()), modal?.date)
        assertEquals(TransactionType.EXPENSE, modal?.type)
        // The default Wallet is the first spendable one — a Contact Wallet
        // never defaults (ADR-0017: an Expense on one is a deliberate pick).
        assertEquals(2, modal?.walletId)
    }

    @Test
    fun `update an expense sends category id null to clear it`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedCategories(category(1, "Food", CategoryType.EXPENSE))
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1, categoryId = 1))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onAmountChange("6.00")
        viewModel.onCategoryChange(null)
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/transactions/1")
        assertTrue(patch.body.contains("\"category_id\":null"))
        val update = json.decodeFromString<TransactionExpenseIncomeUpdateRequest>(patch.body)
        assertNull(update.category_id)
        assertEquals("6.00", update.amount)
    }

    @Test
    fun `update a transfer omits category id entirely`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedTransactions(
            transaction(1, TransactionType.TRANSFER, "20.00", "2026-08-01", sourceWalletId = 1, destinationWalletId = 2),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onAmountChange("30.00")
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/transactions/1")
        assertFalse(patch.body.contains("category_id"))
        val update = json.decodeFromString<TransactionTransferUpdateRequest>(patch.body)
        assertEquals("30.00", update.amount)
    }

    @Test
    fun `delete is tap-again confirmed and the warning flag surfaces`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1))
        deleteWarning = true
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onDeleteTap()
        assertTrue(viewModel.uiState.value.modal!!.confirmingDelete)
        viewModel.onDeleteTap()
        awaitState { it.modal == null && !it.transactions.any { t -> t.id == 1 } }

        assertEquals("Deleted — this made a Cash wallet negative.", viewModel.uiState.value.savedWarning)
        assertTrue(calls.toList().any { it.method == "DELETE" && it.path == "/api/transactions/1" })
    }

    @Test
    fun `create warning flag surfaces as the saved banner`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "10.00"))
        createWarning = true
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("50.00")
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.id == 1 } }

        assertEquals("Saved — this made a Cash wallet negative.", viewModel.uiState.value.savedWarning)
    }

    @Test
    fun `create and update failures map through the web's error contract`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        createStatus = 409
        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal?.error != null }
        assertEquals("A wallet or category with this name already exists.", viewModel.uiState.value.modal?.error)

        createStatus = 422
        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal?.error == "Check the fields and try again." }
        viewModel.closeModal()

        updateStatus = 500
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1))
        DataVersion.bump()
        awaitState { it.transactions.any { t -> t.id == 1 } }
        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onAmountChange("6.00")
        viewModel.submit()
        awaitState { it.modal?.error == "Could not save the transaction." }
    }

    // --- Inline entity creation (ADR-0013/0014, ticket #21) ---

    @Test
    fun `a wallet created from the expense wallet field is real selects and the draft survives`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.onDescriptionChange("Lunch")
        viewModel.onWalletAdd(WalletFieldTarget.WALLET)
        val opened = viewModel.uiState.value.walletCreate
        assertNotNull(opened)
        // An Expense's Wallet field may create any type — Contact included —
        // so nothing is locked (null, like the web's unrestricted modal).
        assertNull(opened?.allowedTypes)

        viewModel.onWalletCreateNameChange("Marco")
        viewModel.onWalletCreateTypeChange(WalletType.CONTACT)
        viewModel.submitWalletCreate()
        awaitState { it.walletCreate == null && it.wallets.any { w -> w.name == "Marco" } }

        val create = json.decodeFromString<WalletCreateRequest>(call("POST", "/api/wallets").body)
        assertEquals("Marco", create.name)
        assertEquals(WalletType.CONTACT, create.type)
        assertEquals("0.00", create.opening_balance)
        // Real at once: the Wallet is selectable in the open form, whose
        // draft is untouched — nothing was sent to the ledger yet.
        val state = viewModel.uiState.value
        assertEquals(2, state.modal?.walletId)
        assertEquals("5.00", state.modal?.amount)
        assertEquals("Lunch", state.modal?.description)
        assertTrue(state.modal!!.canSubmit)
        assertTrue(calls.toList().none { it.method == "POST" && it.path == "/api/transactions" })

        // The form submits immediately against the fresh Wallet.
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.id == 1 } }
        val saved = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals(2, saved.wallet_id)
    }

    @Test
    fun `a wallet created from a transfer's From field selects into From only`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.TRANSFER)
        viewModel.onAmountChange("10.00")
        viewModel.onWalletAdd(WalletFieldTarget.SOURCE)
        assertNotNull(viewModel.uiState.value.walletCreate)
        // A Transfer's From/To may create a Contact Wallet: nothing locked.
        assertNull(viewModel.uiState.value.walletCreate?.allowedTypes)

        viewModel.onWalletCreateNameChange("Marco")
        viewModel.submitWalletCreate()
        awaitState { it.walletCreate == null && it.modal?.sourceWalletId == 3 }

        val state = viewModel.uiState.value
        // Only the From field auto-selects; To keeps its pick.
        assertEquals(3, state.modal?.sourceWalletId)
        assertEquals(2, state.modal?.destinationWalletId)
        assertEquals("10.00", state.modal?.amount)
    }

    @Test
    fun `a wallet created from a transfer's To field selects into To only`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.TRANSFER)
        viewModel.onAmountChange("10.00")
        viewModel.onWalletAdd(WalletFieldTarget.DESTINATION)
        viewModel.onWalletCreateNameChange("Marco")
        viewModel.submitWalletCreate()
        awaitState { it.walletCreate == null && it.modal?.destinationWalletId == 3 }

        // Only the To field auto-selects; From keeps its pick — and the two
        // stay distinct, so the form can submit immediately.
        val state = viewModel.uiState.value
        assertEquals(1, state.modal?.sourceWalletId)
        assertEquals(3, state.modal?.destinationWalletId)
        assertTrue(state.modal!!.canSubmit)
    }

    @Test
    fun `an income's wallet sentinel locks contact out of the create form`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onWalletAdd(WalletFieldTarget.WALLET)
        assertNull(viewModel.uiState.value.walletCreate?.allowedTypes)
        viewModel.cancelWalletCreate()
        // Cancelling only the inline form: the draft's Wallet pick survives.
        assertEquals(1, viewModel.uiState.value.modal?.walletId)
        assertNull(viewModel.uiState.value.walletCreate)

        viewModel.onTypeChange(TransactionType.INCOME)
        viewModel.onWalletAdd(WalletFieldTarget.WALLET)
        assertEquals(NON_CONTACT_WALLET_TYPES, viewModel.uiState.value.walletCreate?.allowedTypes)
        assertFalse(WalletType.CONTACT in viewModel.uiState.value.walletCreate!!.allowedTypes!!)
    }

    @Test
    fun `a category created from an expense form is locked to expense and auto-selects`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.onCategoryAdd()
        val opened = viewModel.uiState.value.categoryCreate
        assertNotNull(opened)
        assertEquals(CategoryType.EXPENSE, opened?.lockedType)

        viewModel.onCategoryCreateNameChange("Groceries")
        viewModel.onCategoryCreateIconChange("🛒")
        viewModel.onCategoryCreateColorChange("#10b981")
        viewModel.submitCategoryCreate()
        awaitState { it.categoryCreate == null && it.categories.any { c -> c.name == "Groceries" } }

        val create = json.decodeFromString<CategoryCreateRequest>(call("POST", "/api/categories").body)
        assertEquals("Groceries", create.name)
        assertEquals(CategoryType.EXPENSE, create.type)
        assertEquals("🛒", create.icon)
        assertEquals("#10b981", create.color)
        // The open form now carries the fresh Category; the draft survives.
        val state = viewModel.uiState.value
        assertEquals(1, state.modal?.categoryId)
        assertEquals("5.00", state.modal?.amount)
        assertTrue(state.modal!!.canSubmit)
        assertTrue(calls.toList().none { it.method == "POST" && it.path == "/api/transactions" })

        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.id == 1 } }
        val saved = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals(1, saved.category_id)
    }

    @Test
    fun `an income's category sentinel locks to income`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.INCOME)
        viewModel.onCategoryAdd()
        val create = viewModel.uiState.value.categoryCreate
        assertNotNull(create)
        assertEquals(CategoryType.INCOME, create?.lockedType)
        assertEquals(CategoryType.INCOME, create?.modal?.type)
    }

    @Test
    fun `an inline create failure stays in the create form and the draft survives`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedCategories(category(1, "Food", CategoryType.EXPENSE))
        createViewModel()
        awaitLoaded()

        walletCreateStatus = 409
        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.onWalletAdd(WalletFieldTarget.WALLET)
        viewModel.onWalletCreateNameChange("Cash")
        viewModel.submitWalletCreate()
        awaitState { it.walletCreate?.modal?.error != null }
        assertEquals("A wallet with this name already exists.", viewModel.uiState.value.walletCreate?.modal?.error)
        assertFalse(viewModel.uiState.value.walletCreate!!.modal.submitting)
        // The outer Transaction draft is untouched and still open.
        assertEquals("5.00", viewModel.uiState.value.modal?.amount)
        assertEquals(1, viewModel.uiState.value.modal?.walletId)

        viewModel.cancelWalletCreate()
        categoryCreateStatus = 409
        viewModel.onCategoryAdd()
        viewModel.onCategoryCreateNameChange("Food")
        viewModel.submitCategoryCreate()
        awaitState { it.categoryCreate?.modal?.error != null }
        assertEquals("A category with this name already exists.", viewModel.uiState.value.categoryCreate?.modal?.error)
        assertEquals("5.00", viewModel.uiState.value.modal?.amount)
    }

    @Test
    fun `a negative opening balance blocks the inline wallet create`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onWalletAdd(WalletFieldTarget.WALLET)
        viewModel.onWalletCreateNameChange("New")
        viewModel.onWalletCreateOpeningBalanceChange("-5")
        viewModel.submitWalletCreate()

        assertEquals("Enter an amount of €0 or more.", viewModel.uiState.value.walletCreate?.modal?.error)
        assertTrue(calls.toList().none { it.method == "POST" && it.path == "/api/wallets" })
    }

    @Test
    fun `a category can be created inline while editing an uncategorized expense`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1))
        createViewModel()
        awaitLoaded()

        // The Category field stays live while editing (web parity); the
        // Wallet fields freeze, so only a Category can be created inline.
        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onCategoryAdd()
        val create = viewModel.uiState.value.categoryCreate
        assertNotNull(create)
        assertEquals(CategoryType.EXPENSE, create?.lockedType)

        viewModel.onCategoryCreateNameChange("Groceries")
        viewModel.submitCategoryCreate()
        awaitState { it.categoryCreate == null && it.modal?.categoryId == 1 }

        // The edit draft survives: the amount and wallet are untouched, the
        // fresh Category rides on the update.
        val state = viewModel.uiState.value
        assertEquals(1, state.modal?.editing?.id)
        assertEquals("5.00", state.modal?.amount)
        assertEquals(1, state.modal?.categoryId)
        viewModel.submit()
        awaitState { it.modal == null }
        val patch = json.decodeFromString<TransactionExpenseIncomeUpdateRequest>(
            call("PATCH", "/api/transactions/1").body,
        )
        assertEquals(1, patch.category_id)
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

        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto =
            error("unused in the debounce test")

        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
            error("unused in the debounce test")

        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto =
            error("unused in the debounce test")
    }
}
