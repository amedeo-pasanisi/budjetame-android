package com.budjetame.android.ui.transactions

import androidx.lifecycle.viewModelScope
import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.CategoryCreateRequest
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringCostCreateRequest
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeApi
import com.budjetame.android.data.api.RecurringIncomeCreateRequest
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionCreateRequest
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionExpenseIncomeUpdateRequest
import com.budjetame.android.data.api.TransactionExpenseLinkUpdateRequest
import com.budjetame.android.data.api.TransactionIncomeLinkUpdateRequest
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionTransferUpdateRequest
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.api.WalletCreateRequest
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.ApiCategoryRepository
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.ApiRecurringCostRepository
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.ApiRecurringIncomeRepository
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.ApiTransactionRepository
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.ApiWalletRepository
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.util.Dates
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val EXPORT_CONTENT_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

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
    private val recurringCostStore = mutableListOf<RecurringCostDto>()
    private val recurringIncomeStore = mutableListOf<RecurringIncomeDto>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var listStatus = 200
    private var loadMoreStatus = 200
    private var walletsStatus = 200
    private var categoriesStatus = 200
    private var recurringCostsStatus = 200
    private var recurringIncomesStatus = 200
    private var createStatus = 201
    private var walletCreateStatus = 201
    private var categoryCreateStatus = 201
    private var updateStatus = 200
    private var deleteStatus = 200
    private var exportStatus = 200
    private var createWarning = false
    private var updateWarning = false
    private var deleteWarning = false

    private val json = Json { ignoreUnknownKeys = true }

    /** The fake device GPS (ticket #29): permission state and a canned
     * position, plus an optional "ask" mode — when `granted` is false the
     * ViewModel raises the modal's requestingLocationPermission flag and
     * suspends until the test answers through `onLocationPermissionResult`
     * (the screen's half of the bridge). */
    private class FakeLocation : DeviceLocation {
        var granted = true
        var position: LatLng? = null
        var fetchCount = 0
            private set

        override fun permissionGranted(): Boolean = granted

        override suspend fun currentPosition(): LatLng? {
            fetchCount++
            return position
        }
    }

    private var location = FakeLocation()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        transactionStore.clear()
        walletStore.clear()
        categoryStore.clear()
        recurringCostStore.clear()
        recurringIncomeStore.clear()
        calls.clear()
        listStatus = 200
        loadMoreStatus = 200
        walletsStatus = 200
        categoriesStatus = 200
        recurringCostsStatus = 200
        recurringIncomesStatus = 200
        createStatus = 201
        walletCreateStatus = 201
        categoryCreateStatus = 201
        updateStatus = 200
        deleteStatus = 200
        exportStatus = 200
        createWarning = false
        updateWarning = false
        deleteWarning = false
        location = FakeLocation()
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

    private fun createViewModel(searchDebounceMillis: Long = 0, seed: LedgerJump? = null) {
        val client = ApiClient(server.url("/api/").toString()) { null }
        val transactions = ApiTransactionRepository(client.create(TransactionApi::class.java))
        val wallets = ApiWalletRepository(client.create(WalletApi::class.java))
        val categories = ApiCategoryRepository(client.create(CategoryApi::class.java))
        val recurringCosts = ApiRecurringCostRepository(client.create(RecurringCostApi::class.java))
        val recurringIncomes = ApiRecurringIncomeRepository(client.create(RecurringIncomeApi::class.java))
        viewModel = TransactionsViewModel(
            transactions = transactions,
            wallets = wallets,
            categories = categories,
            recurringCosts = recurringCosts,
            recurringIncomes = recurringIncomes,
            searchDebounceMillis = searchDebounceMillis,
            location = location,
            seed = seed,
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

            method == "GET" && path == "/api/recurring-costs" -> when {
                recurringCostsStatus != 200 -> jsonResponse(recurringCostsStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(recurringCostStore))
            }

            method == "GET" && path == "/api/recurring-incomes" -> when {
                recurringIncomesStatus != 200 -> jsonResponse(recurringIncomesStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(recurringIncomeStore))
            }

            method == "GET" && path == "/api/transactions/export" -> exportResponse()

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
        query["recurring_cost_id"]?.let { id ->
            filtered = filtered.filter { it.recurring_cost_id == id.toInt() }
        }
        query["recurring_income_id"]?.let { id ->
            filtered = filtered.filter { it.recurring_income_id == id.toInt() }
        }
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
     * for the seam: the form itself gates the rest client-side. A linked
     * Expense receives the link's stored pin — the Occurrence the link pays
     * at link time (web issue #57): the fake names the cost's oldest Unpaid
     * Occurrence, exactly what its list view advertises. */
    private fun createTransaction(body: String): MockResponse {
        if (createStatus != 201) return jsonResponse(createStatus, """{"detail":"boom"}""")
        val create = json.decodeFromString<TransactionCreateRequest>(body)
        createRuleError(create)?.let { error ->
            return jsonResponse(422, """{"detail":"$error"}""")
        }
        if (create.recurring_cost_id != null && create.type != TransactionType.EXPENSE) {
            return jsonResponse(422, """{"detail":"Only Expenses can be linked to a Recurring Cost"}""")
        }
        if (create.recurring_income_id != null && create.type != TransactionType.INCOME) {
            return jsonResponse(422, """{"detail":"Only Incomes can be linked to a Recurring Income"}""")
        }
        val id = (transactionStore.maxOfOrNull { it.id } ?: 0) + 1
        val pin = create.recurring_cost_id?.let { costId ->
            recurringCostStore.find { it.id == costId }?.next_unpaid_occurrence_date
        } ?: create.recurring_income_id?.let { incomeId ->
            recurringIncomeStore.find { it.id == incomeId }?.next_unpaid_occurrence_date
        }
        val created = TransactionDto(
            id = id,
            type = create.type,
            amount = create.amount,
            date = create.date,
            wallet_id = create.wallet_id,
            source_wallet_id = create.source_wallet_id,
            destination_wallet_id = create.destination_wallet_id,
            category_id = create.category_id,
            recurring_cost_id = create.recurring_cost_id,
            recurring_income_id = create.recurring_income_id,
            occurrence_date = pin,
            description = create.description,
            latitude = create.latitude,
            longitude = create.longitude,
            place_name = create.place_name,
            place_id = create.place_id,
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
                latitude = update.latitude,
                longitude = update.longitude,
                place_name = update.place_name,
                place_id = update.place_id,
                warning = updateWarning,
            )
        } else {
            // The Expense link PATCH carries the recurring_cost_id key — a
            // value links (paying the cost's oldest Unpaid Occurrence, the
            // pin the list advertises), null unlinks, freeing the pin; the
            // Income link PATCH carries the recurring_income_id key under
            // the same contract; without the key the stored link is
            // untouched. Expense PATCHes never carry the income key and
            // Income PATCHes never the cost key, like the backend's
            // contract (a mismatched key is rejected).
            val bodyObject = json.parseToJsonElement(body).jsonObject
            if (bodyObject.containsKey("recurring_income_id") &&
                current.type != TransactionType.INCOME
            ) {
                return jsonResponse(422, """{"detail":"Only Incomes can be linked to a Recurring Income"}""")
            }
            if (bodyObject.containsKey("recurring_cost_id") &&
                current.type != TransactionType.EXPENSE
            ) {
                return jsonResponse(422, """{"detail":"Only Expenses can be linked to a Recurring Cost"}""")
            }
            if (current.type == TransactionType.EXPENSE && bodyObject.containsKey("recurring_cost_id")) {
                val update = json.decodeFromString<TransactionExpenseLinkUpdateRequest>(body)
                val pin = update.recurring_cost_id?.let { costId ->
                    recurringCostStore.find { it.id == costId }?.next_unpaid_occurrence_date
                }
                current.copy(
                    amount = update.amount,
                    date = update.date,
                    category_id = update.category_id,
                    recurring_cost_id = update.recurring_cost_id,
                    occurrence_date = pin,
                    description = update.description,
                    latitude = update.latitude,
                    longitude = update.longitude,
                    place_name = update.place_name,
                    place_id = update.place_id,
                    warning = updateWarning,
                )
            } else if (current.type == TransactionType.INCOME && bodyObject.containsKey("recurring_income_id")) {
                val update = json.decodeFromString<TransactionIncomeLinkUpdateRequest>(body)
                val pin = update.recurring_income_id?.let { incomeId ->
                    recurringIncomeStore.find { it.id == incomeId }?.next_unpaid_occurrence_date
                }
                current.copy(
                    amount = update.amount,
                    date = update.date,
                    category_id = update.category_id,
                    recurring_income_id = update.recurring_income_id,
                    occurrence_date = pin,
                    description = update.description,
                    latitude = update.latitude,
                    longitude = update.longitude,
                    place_name = update.place_name,
                    place_id = update.place_id,
                    warning = updateWarning,
                )
            } else {
                val update = json.decodeFromString<TransactionExpenseIncomeUpdateRequest>(body)
                current.copy(
                    amount = update.amount,
                    date = update.date,
                    category_id = update.category_id,
                    description = update.description,
                    latitude = update.latitude,
                    longitude = update.longitude,
                    place_name = update.place_name,
                    place_id = update.place_id,
                    warning = updateWarning,
                )
            }
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

    /** The fake export (US 7.3, ticket #28): the file the backend produces
     * for the ledger under the current filters — a canned workbook
     * recorded from the import template's exporter (its cells are pinned
     * in ExportFileTest) — with the server's dated Content-Disposition
     * filename. The export's content contract — no Opening Balance rows,
     * no recurring links, Places flattened to coordinates — is applied
     * server-side (CONTEXT.md, verified end to end in the web repo's
     * backend suite); the seam test pins the client's half of it: the
     * request carries exactly the ledger's filters, and the mapping hands
     * the file through byte-for-byte under the server's name. */
    private fun exportResponse(): MockResponse {
        if (exportStatus != 200) return jsonResponse(exportStatus, """{"detail":"boom"}""")
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", EXPORT_CONTENT_TYPE)
            .setHeader("Content-Disposition", "attachment; filename=\"budjetame-2026-08-23.xlsx\"")
            .setBody(Buffer().write(exportFixture()))
    }

    /** The recorded export fixture (src/test/resources/export/ledger-export.xlsx). */
    private fun exportFixture(): ByteArray =
        checkNotNull(
            TransactionsViewModelTest::class.java.getResourceAsStream("/export/ledger-export.xlsx"),
        ) { "missing test resource export/ledger-export.xlsx" }.use { it.readBytes() }

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

    private fun seedRecurringCosts(vararg costs: RecurringCostDto) {
        recurringCostStore.addAll(costs)
    }

    private fun seedRecurringIncomes(vararg incomes: RecurringIncomeDto) {
        recurringIncomeStore.addAll(incomes)
    }

    // --- Ledger jump (ADR-0004, ticket #44; the Recurring kinds are web
    // ADR-0026 / ticket #46) ---

    /** The ledger listing GETs so far — each reload is exactly one. */
    private fun ledgerFetchCount(): Int =
        calls.count { it.method == "GET" && it.path == "/api/transactions" }

    /** Wait until [count] ledger GETs have happened (the fetches run on
     * OkHttp's own threads, outside the test dispatcher). */
    private fun awaitLedgerFetches(count: Int) {
        runBlocking {
            withTimeout(5_000) {
                while (ledgerFetchCount() < count) delay(10)
            }
        }
    }

    @Test
    fun `a seed wallet jump loads the first page already filtered in one fetch`() {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(5, "Marco", WalletType.CONTACT, "0.00"),
        )
        transactionStore += transaction(1, TransactionType.EXPENSE, "1.00", "2026-08-01", walletId = 1)
        transactionStore += transaction(2, TransactionType.EXPENSE, "2.00", "2026-08-02", walletId = 5)

        createViewModel(seed = LedgerJump.Wallet(5))
        awaitLedgerFetches(1)
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.loading } } }

        // One fetch, already filtered: the first-ever visit via a jump never
        // flashes the unfiltered ledger and never fetches twice.
        assertEquals(1, ledgerFetchCount())
        val ledger = calls.filter { it.method == "GET" && it.path == "/api/transactions" }.single()
        assertEquals("5", ledger.query["wallet_id"])
        assertNull(ledger.query["category_id"])
        assertNull(ledger.query["q"])
        assertEquals(listOf(2), viewModel.uiState.value.transactions.map { it.id })
        assertEquals(5, viewModel.uiState.value.filterWalletId)
        assertFalse(viewModel.uiState.value.filtersOpen)
    }

    @Test
    fun `applying the jump the state already carries never refetches`() {
        seedCategories(category(3, "Food", CategoryType.EXPENSE))

        createViewModel(seed = LedgerJump.Category(3))
        awaitLedgerFetches(1)
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.loading } } }
        assertEquals(1, ledgerFetchCount())

        // The screen still applies-and-consumes once on a seeded visit: the
        // state already matches, so the call is a no-op — no second fetch.
        viewModel.applyLedgerJump(LedgerJump.Category(3))
        assertEquals(1, ledgerFetchCount())
        assertEquals(3, viewModel.uiState.value.filterCategoryId)
        assertTrue(viewModel.uiState.value.search.isEmpty())
    }

    @Test
    fun `an applied jump replaces every filter clears the search and closes the panel in one refetch`() {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedCategories(category(3, "Food", CategoryType.EXPENSE))
        transactionStore += transaction(1, TransactionType.EXPENSE, "1.00", "2026-08-01", walletId = 1)
        transactionStore += transaction(2, TransactionType.EXPENSE, "2.00", "2026-08-02", walletId = 1, categoryId = 3)

        createViewModel()
        awaitLedgerFetches(1)
        viewModel.onFilterWalletChange(1)
        awaitLedgerFetches(2)
        viewModel.onSearchChange("food")
        awaitLedgerFetches(3)
        viewModel.toggleFilters()
        viewModel.onFilterFromDateChange("2026-08-01")
        awaitLedgerFetches(4)

        viewModel.applyLedgerJump(LedgerJump.Category(3))
        awaitLedgerFetches(5)
        runBlocking {
            withTimeout(5_000) {
                viewModel.uiState.first { it.transactions.map { t -> t.id } == listOf(2) }
            }
        }

        val state = viewModel.uiState.value
        assertNull(state.filterWalletId)
        assertEquals(3, state.filterCategoryId)
        assertNull(state.filterFromDate)
        assertNull(state.filterToDate)
        assertNull(state.filterRecurring)
        assertTrue(state.search.isEmpty())
        assertTrue(state.searchNeedle.isEmpty())
        assertFalse(state.filtersOpen)
        val ledger = calls.filter { it.method == "GET" && it.path == "/api/transactions" }.last()
        assertEquals("3", ledger.query["category_id"])
        assertNull(ledger.query["wallet_id"])
        assertNull(ledger.query["q"])
        assertEquals(listOf(2), state.transactions.map { it.id })
    }

    @Test
    fun `a seed recurring-cost jump loads the first page already filtered in one fetch`() {
        seedRecurringCosts(recurringCost(3, "Rent"))
        transactionStore += transaction(1, TransactionType.EXPENSE, "1.00", "2026-08-01", walletId = 1, recurringCostId = 3)
        transactionStore += transaction(2, TransactionType.EXPENSE, "2.00", "2026-08-02", walletId = 1)

        createViewModel(seed = LedgerJump.RecurringCost(3))
        awaitLedgerFetches(1)
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.loading } } }

        // One fetch, already filtered: the first-ever visit via a jump never
        // flashes the unfiltered ledger and never fetches twice.
        assertEquals(1, ledgerFetchCount())
        val ledger = calls.filter { it.method == "GET" && it.path == "/api/transactions" }.single()
        assertEquals("3", ledger.query["recurring_cost_id"])
        assertNull(ledger.query["wallet_id"])
        assertNull(ledger.query["recurring_income_id"])
        assertEquals(listOf(1), viewModel.uiState.value.transactions.map { it.id })
        assertEquals(
            RecurringFilter(RecurringFilterKind.COST, 3),
            viewModel.uiState.value.filterRecurring,
        )
        assertFalse(viewModel.uiState.value.filtersOpen)
    }

    @Test
    fun `applying a recurring jump the state already carries never refetches`() {
        seedRecurringIncomes(recurringIncome(5, "Salary"))
        transactionStore += transaction(1, TransactionType.INCOME, "1.00", "2026-08-01", walletId = 1, recurringIncomeId = 5)

        createViewModel(seed = LedgerJump.RecurringIncome(5))
        awaitLedgerFetches(1)
        runBlocking { withTimeout(5_000) { viewModel.uiState.first { !it.loading } } }
        assertEquals(1, ledgerFetchCount())

        // The screen still applies-and-consumes once on a seeded visit: the
        // state already matches, so the call is a no-op — no second fetch.
        viewModel.applyLedgerJump(LedgerJump.RecurringIncome(5))
        assertEquals(1, ledgerFetchCount())
        assertEquals(
            RecurringFilter(RecurringFilterKind.INCOME, 5),
            viewModel.uiState.value.filterRecurring,
        )
        assertTrue(viewModel.uiState.value.search.isEmpty())
    }

    @Test
    fun `an applied recurring jump replaces every filter including a manual recurring pick in one refetch`() {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedRecurringCosts(recurringCost(3, "Rent"))
        seedRecurringIncomes(recurringIncome(5, "Salary"))
        transactionStore += transaction(1, TransactionType.EXPENSE, "1.00", "2026-08-01", walletId = 1, recurringCostId = 3)
        transactionStore += transaction(2, TransactionType.INCOME, "2.00", "2026-08-02", walletId = 1, recurringIncomeId = 5)

        createViewModel()
        awaitLedgerFetches(1)
        viewModel.onFilterWalletChange(1)
        awaitLedgerFetches(2)
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.INCOME, 5))
        awaitLedgerFetches(3)

        viewModel.applyLedgerJump(LedgerJump.RecurringCost(3))
        awaitLedgerFetches(4)
        runBlocking {
            withTimeout(5_000) {
                viewModel.uiState.first { it.transactions.map { t -> t.id } == listOf(1) }
            }
        }

        val state = viewModel.uiState.value
        assertNull(state.filterWalletId)
        assertNull(state.filterCategoryId)
        assertNull(state.filterFromDate)
        assertNull(state.filterToDate)
        assertEquals(
            RecurringFilter(RecurringFilterKind.COST, 3),
            state.filterRecurring,
        )
        assertTrue(state.search.isEmpty())
        assertFalse(state.filtersOpen)
        val ledger = calls.filter { it.method == "GET" && it.path == "/api/transactions" }.last()
        assertEquals("3", ledger.query["recurring_cost_id"])
        assertNull(ledger.query["wallet_id"])
        assertNull(ledger.query["recurring_income_id"])
        assertEquals(listOf(1), state.transactions.map { it.id })
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
        recurringCostId: Int? = null,
        recurringIncomeId: Int? = null,
        occurrenceDate: String? = null,
        description: String? = null,
        latitude: String? = null,
        longitude: String? = null,
        placeName: String? = null,
        placeId: String? = null,
    ) = TransactionDto(
        id = id,
        type = type,
        amount = amount,
        date = date,
        wallet_id = walletId,
        source_wallet_id = sourceWalletId,
        destination_wallet_id = destinationWalletId,
        category_id = categoryId,
        recurring_cost_id = recurringCostId,
        recurring_income_id = recurringIncomeId,
        occurrence_date = occurrenceDate,
        description = description,
        latitude = latitude,
        longitude = longitude,
        place_name = placeName,
        place_id = placeId,
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun wallet(id: Int, name: String, type: WalletType, balance: String, frozen: Boolean = false) =
        WalletDto(id, name, type, balance, frozen, "2026-08-01T10:00:00Z")

    private fun category(id: Int, name: String, type: CategoryType, icon: String? = null) =
        CategoryDto(id, name, type, icon, "#000000", "2026-08-01T10:00:00Z")

    private fun recurringCost(
        id: Int,
        name: String,
        nextUnpaid: String = "2026-08-05",
    ) = RecurringCostDto(
        id = id,
        name = name,
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = "2026-09-05",
        next_unpaid_occurrence_date = nextUnpaid,
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun recurringIncome(
        id: Int,
        name: String,
        nextUnpaid: String = "2026-08-05",
    ) = RecurringIncomeDto(
        id = id,
        name = name,
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = "2026-09-05",
        next_unpaid_occurrence_date = nextUnpaid,
        created_at = "2026-08-01T10:00:00Z",
    )
    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (TransactionsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun listCalls(): List<RecordedCall> =
        calls.toList().filter { it.path == "/api/transactions" }

    private fun exportCalls(): List<RecordedCall> =
        calls.toList().filter { it.path == "/api/transactions/export" }

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

    /** The most recent request to a URL — later rounds hit the same path. */
    private fun lastCall(method: String, path: String): RecordedCall =
        calls.toList().last { it.method == method && it.path == path }

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

    // --- Recurring definition filter (web issue #86, ticket #25) ---

    @Test
    fun `the recurring filter narrows to exactly the picked definition under its own key`() = runBlocking {
        // A Recurring Cost and a Recurring Income may share an id (and a
        // name): the pick's kind decides the wire key, so the two can
        // never be confused.
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedRecurringIncomes(recurringIncome(1, "Salary"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, recurringCostId = 1, occurrenceDate = "2026-08-01", description = "Linked rent"),
            transaction(2, TransactionType.INCOME, "2500.00", "2026-08-02", walletId = 1, recurringIncomeId = 1, occurrenceDate = "2026-08-02", description = "Linked salary"),
            transaction(3, TransactionType.EXPENSE, "5.00", "2026-08-03", walletId = 1, description = "Coffee"),
        )
        createViewModel()
        awaitLoaded()
        assertEquals(listOf(3, 2, 1), viewModel.uiState.value.transactions.map { it.id })

        // Picking the Rent cost narrows to its linked Expense and sends
        // recurring_cost_id — never the income key, despite the shared id.
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        awaitState { it.transactions.map { t -> t.id } == listOf(1) }

        var last = listCalls().last()
        assertEquals("1", last.query["recurring_cost_id"])
        assertNull(last.query["recurring_income_id"])
        assertNull(last.query["cursor"])
        assertTrue(viewModel.uiState.value.filtersActive)
        assertEquals("No transactions match these filters.", viewModel.uiState.value.emptyMessage)

        // Picking the same-id Salary income narrows to its own linked
        // Income and sends the income key instead.
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.INCOME, 1))
        awaitState { it.transactions.map { t -> t.id } == listOf(2) }

        last = listCalls().last()
        assertEquals("1", last.query["recurring_income_id"])
        assertNull(last.query["recurring_cost_id"])
        assertNull(last.query["cursor"])

        // Clearing restores the full ledger with neither key.
        viewModel.onFilterRecurringChange(null)
        awaitState { it.transactions.map { t -> t.id } == listOf(3, 2, 1) }

        last = listCalls().last()
        assertNull(last.query["recurring_cost_id"])
        assertNull(last.query["recurring_income_id"])
        assertFalse(viewModel.uiState.value.filtersActive)
    }

    @Test
    fun `the recurring filter composes with wallet category date and search`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedCategories(category(1, "Housing", CategoryType.EXPENSE), category(2, "Food", CategoryType.EXPENSE))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, categoryId = 1, recurringCostId = 1, description = "Rent August"),
            transaction(2, TransactionType.EXPENSE, "800.00", "2026-08-02", walletId = 1, categoryId = 1, recurringCostId = 1, description = "Rent September"),
            transaction(3, TransactionType.EXPENSE, "6.00", "2026-08-01", walletId = 2, categoryId = 2, description = "Lunch"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onFilterWalletChange(1)
        viewModel.onFilterCategoryChange(1)
        viewModel.onFilterFromDateChange("2026-08-01")
        viewModel.onFilterToDateChange("2026-08-01")
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        viewModel.onSearchChange("august")
        // Only the row matching every bound survives: wallet 1 ∧ category 1
        // ∧ 2026-08-01 ∧ cost 1 ∧ the "august" needle.
        awaitState { it.transactions.map { t -> t.id } == listOf(1) }

        val last = listCalls().last()
        assertEquals("1", last.query["wallet_id"])
        assertEquals("1", last.query["category_id"])
        assertEquals("2026-08-01", last.query["from_date"])
        assertEquals("2026-08-01", last.query["to_date"])
        assertEquals("1", last.query["recurring_cost_id"])
        assertNull(last.query["recurring_income_id"])
        assertEquals("august", last.query["q"])
        assertNull(last.query["cursor"])
    }

    @Test
    fun `paging under a recurring filter keeps the key and hands the cursor back`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedTransactions(
            *(1..55).map {
                transaction(it, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, recurringCostId = 1)
            }.toTypedArray() + transaction(56, TransactionType.EXPENSE, "5.00", "2026-08-02", walletId = 1),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        // The unlinked row never appears: only the definition's rows page.
        // (The predicate awaits the *filtered* first page — the unfiltered
        // load also holds 50 rows plus a cursor.)
        awaitState {
            it.transactions.size == 50 && it.nextCursor != null && it.transactions.none { t -> t.id == 56 }
        }
        val cursor = viewModel.uiState.value.nextCursor ?: error("expected a next cursor")

        viewModel.loadMore()
        awaitState { it.transactions.size == 55 }

        val calls = listCalls()
        assertEquals(3, calls.size)
        // The filter key rides on every page, and the first page's opaque
        // cursor travels back verbatim.
        assertEquals("1", calls[1].query["recurring_cost_id"])
        assertEquals("1", calls[2].query["recurring_cost_id"])
        assertEquals(cursor, calls[2].query["cursor"])
        assertNull(calls[2].query["recurring_income_id"])
        assertTrue(viewModel.uiState.value.transactions.all { it.recurring_cost_id == 1 })
        assertNull(viewModel.uiState.value.nextCursor)
    }

    @Test
    fun `reselecting the same recurring definition does not refetch`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedRecurringCosts(recurringCost(1, "Rent"))
        createViewModel()
        awaitLoaded()

        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        withTimeout(5_000) {
            while (listCalls().size < 2) delay(10)
        }
        // The same pick again is a no-op, like the web's React bail-out.
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        delay(100)
        assertEquals(2, listCalls().size)

        // A real change refetches.
        viewModel.onFilterRecurringChange(null)
        withTimeout(5_000) {
            while (listCalls().size < 3) delay(10)
        }
    }

    @Test
    fun `the recurring options ride on the ledger load and every bump refreshes them`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedRecurringIncomes(recurringIncome(1, "Salary"))
        createViewModel()
        awaitLoaded()

        // No form is open: the filter bar's select is what the definitions
        // serve, so the ledger load fetches them like the wallets and the
        // categories.
        awaitState { it.recurringCosts.any { c -> c.name == "Rent" } && it.recurringIncomes.any { i -> i.name == "Salary" } }

        // A write elsewhere (the Recurring tab creating a definition) bumps
        // the data version; the background reload refreshes the options too.
        seedRecurringCosts(recurringCost(1, "Rent"), recurringCost(2, "Gym"))
        DataVersion.bump()
        awaitState { it.recurringCosts.any { c -> c.name == "Gym" } }
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
        viewModel = TransactionsViewModel(gateway, gateway, gateway, gateway, gateway, location = location)
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

    // --- Filtered-line and panel clears (web issue #92, ticket #35) ---

    @Test
    fun `the date chip's clear resets both bounds in one refetch`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1),
            transaction(2, TransactionType.EXPENSE, "6.00", "2026-09-01", walletId = 1),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onFilterFromDateChange("2026-08-01")
        viewModel.onFilterToDateChange("2026-08-31")
        awaitState { it.transactions.map { t -> t.id } == listOf(1) }

        val before = listCalls().size
        viewModel.clearFilterDates()
        awaitState { it.filterFromDate == null && it.filterToDate == null && it.transactions.size == 2 }

        // One chip, one ✕, one date-range filter: both bounds reset in a
        // single refetch, never two.
        withTimeout(5_000) {
            while (listCalls().size < before + 1) delay(10)
        }
        assertEquals(before + 1, listCalls().size)
        val last = listCalls().last()
        assertNull(last.query["from_date"])
        assertNull(last.query["to_date"])
        assertFalse(viewModel.uiState.value.filtersActive)
    }

    @Test
    fun `the panel footer's Clear all filters clears only the panel filters leaving the search`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedCategories(category(1, "Housing", CategoryType.EXPENSE))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, categoryId = 1, recurringCostId = 1, description = "Rent August"),
            transaction(2, TransactionType.EXPENSE, "6.00", "2026-09-01", walletId = 2, description = "Lunch"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onFilterWalletChange(1)
        viewModel.onFilterCategoryChange(1)
        viewModel.onFilterFromDateChange("2026-08-01")
        viewModel.onFilterToDateChange("2026-08-31")
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        viewModel.onSearchChange("august")
        awaitState { it.searchNeedle == "august" && it.transactions.map { t -> t.id } == listOf(1) }

        val before = listCalls().size
        viewModel.clearPanelFilters()
        // The five panel filters go, the search box keeps its text: one
        // refetch with the needle alone — the search's own match stays.
        withTimeout(5_000) {
            while (listCalls().size < before + 1) delay(10)
        }
        assertEquals(before + 1, listCalls().size)
        awaitState {
            !it.filtersActive && it.search == "august" && it.searchNeedle == "august" &&
                it.transactions.map { t -> t.id } == listOf(1)
        }
        val last = listCalls().last()
        assertNull(last.query["wallet_id"])
        assertNull(last.query["category_id"])
        assertNull(last.query["from_date"])
        assertNull(last.query["to_date"])
        assertNull(last.query["recurring_cost_id"])
        assertNull(last.query["recurring_income_id"])
        assertEquals("august", last.query["q"])
    }

    @Test
    fun `the filtered line's Clear all removes the five filters and the search in one refetch`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, description = "Rent August"),
            transaction(2, TransactionType.EXPENSE, "6.00", "2026-09-01", walletId = 2, description = "Lunch"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onFilterWalletChange(1)
        viewModel.onSearchChange("  august ")
        awaitState { it.searchNeedle == "august" && it.transactions.map { t -> t.id } == listOf(1) }

        val before = listCalls().size
        viewModel.clearFiltersAndSearch()
        // Input and debounced needle go together, back to a fully clean
        // list — one refetch with no filters and no q.
        withTimeout(5_000) {
            while (listCalls().size < before + 1) delay(10)
        }
        assertEquals(before + 1, listCalls().size)
        awaitState {
            it.search.isEmpty() && it.searchNeedle.isEmpty() && !it.filtersActive &&
                it.transactions.map { t -> t.id } == listOf(2, 1)
        }
        val last = listCalls().last()
        assertNull(last.query["wallet_id"])
        assertNull(last.query["q"])
        assertNull(last.query["cursor"])
    }

    @Test
    fun `clearing with nothing set never refetches`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        val before = listCalls().size
        viewModel.clearFilterDates()
        viewModel.clearPanelFilters()
        viewModel.clearFiltersAndSearch()
        delay(100)
        assertEquals(before, listCalls().size)
    }

    @Test
    fun `Clear all cancels a pending search debounce so no late refetch resurrects the needle`() {
        val gateway = RecordingGateway()
        viewModel = TransactionsViewModel(gateway, gateway, gateway, gateway, gateway, location = location)
        mainRule.dispatcher.scheduler.runCurrent()
        val callsBefore = gateway.transactionCalls

        viewModel.onSearchChange("caffe")
        // The keystroke is still inside the debounce window: Clear all must
        // cancel the pending job, not leave it to fire afterwards.
        viewModel.clearFiltersAndSearch()
        mainRule.dispatcher.scheduler.advanceTimeBy(TransactionsViewModel.SEARCH_DEBOUNCE_MILLIS)
        mainRule.dispatcher.scheduler.runCurrent()

        // The clear's own single refetch is the only request: the stale
        // needle never comes back.
        assertEquals(callsBefore + 1, gateway.transactionCalls)
        assertEquals("", viewModel.uiState.value.search)
        assertEquals("", viewModel.uiState.value.searchNeedle)
        assertNull(gateway.lastFilters.q)
    }

    // --- Export (US 7.3, ticket #28) ---

    @Test
    fun `export carries the current filters and search and maps the file under the server's filename`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "0.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedCategories(category(1, "Housing", CategoryType.EXPENSE))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, categoryId = 1, recurringCostId = 1, description = "Rent August"),
            transaction(2, TransactionType.EXPENSE, "5.00", "2026-09-01", walletId = 2, description = "Lunch"),
        )
        createViewModel()
        awaitLoaded()

        // The ledger's current filters and search: the export must apply
        // exactly what the list shows — not just the visible page.
        viewModel.onFilterWalletChange(1)
        viewModel.onFilterCategoryChange(1)
        viewModel.onFilterFromDateChange("2026-08-01")
        viewModel.onFilterToDateChange("2026-08-31")
        viewModel.onFilterRecurringChange(RecurringFilter(RecurringFilterKind.COST, 1))
        viewModel.onSearchChange("rent")

        viewModel.export()
        awaitState { it.exportFile != null }

        val export = viewModel.uiState.value.exportFile ?: error("expected an export file")
        assertEquals("budjetame-2026-08-23.xlsx", export.filename)
        // The mapping passes the server's workbook through byte-for-byte.
        assertTrue(export.content.contentEquals(exportFixture()))
        assertNull(viewModel.uiState.value.exportError)
        assertFalse(viewModel.uiState.value.exporting)

        val last = exportCalls().last()
        assertEquals("1", last.query["wallet_id"])
        assertEquals("1", last.query["category_id"])
        assertEquals("2026-08-01", last.query["from_date"])
        assertEquals("2026-08-31", last.query["to_date"])
        assertEquals("1", last.query["recurring_cost_id"])
        assertNull(last.query["recurring_income_id"])
        assertEquals("rent", last.query["q"])
        // The export is the whole matching ledger, never paged.
        assertNull(last.query["limit"])
        assertNull(last.query["cursor"])
    }

    @Test
    fun `an unfiltered export sends no query params`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.export()
        awaitState { it.exportFile != null }

        val export = viewModel.uiState.value.exportFile ?: error("expected an export file")
        assertEquals("budjetame-2026-08-23.xlsx", export.filename)
        assertTrue(export.content.contentEquals(exportFixture()))
        assertTrue(exportCalls().single().query.isEmpty())
    }

    @Test
    fun `a failed export surfaces the web's message and the next press can succeed`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        exportStatus = 500
        viewModel.export()
        awaitState { it.exportError != null }
        assertEquals("Could not export transactions.", viewModel.uiState.value.exportError)
        assertNull(viewModel.uiState.value.exportFile)
        assertFalse(viewModel.uiState.value.exporting)

        // A 422 speaks the fields message like every screen's mapping.
        exportStatus = 422
        viewModel.export()
        awaitState { it.exportError == "Check the fields and try again." }
        assertNull(viewModel.uiState.value.exportFile)

        // The error clears on the next press, and a success lands the file.
        exportStatus = 200
        viewModel.export()
        awaitState { it.exportFile != null }
        assertNull(viewModel.uiState.value.exportError)
        assertEquals(3, exportCalls().size)
    }

    @Test
    fun `a double export press fires one request`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        // The first request's response is held until both presses have been
        // delivered, so the second press deterministically lands while the
        // first export is still in flight.
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.endsWith("/transactions/export") == true) {
                    release.await(5, TimeUnit.SECONDS)
                }
                return route(request)
            }
        }
        viewModel.export()
        viewModel.export()
        release.countDown()
        awaitState { it.exportFile != null }

        // One press, one request: the second press could not fire a
        // concurrent export.
        assertEquals(1, exportCalls().size)
        assertFalse(viewModel.uiState.value.exporting)
    }

    @Test
    fun `the shared file is consumed once and a later press fetches afresh`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "0.00"))
        createViewModel()
        awaitLoaded()

        viewModel.export()
        awaitState { it.exportFile != null }
        val first = viewModel.uiState.value.exportFile ?: error("expected an export file")
        assertEquals(1, exportCalls().size)

        // The screen shared the file and reports back: it clears, so the
        // same file can never leave the app twice.
        viewModel.onExportHandled()
        assertNull(viewModel.uiState.value.exportFile)
        assertNull(viewModel.uiState.value.exportError)

        // A later press exports again — a fresh fetch, not the old file.
        viewModel.export()
        awaitState { it.exportFile != null && it.exportFile !== first }
        assertEquals(2, exportCalls().size)
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

    // --- The Recurring Cost link (web issue #57, ticket #22) ---

    @Test
    fun `creating a linked expense sends the recurring cost id and lands the pin`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringCosts(recurringCost(1, "Rent", nextUnpaid = "2026-08-01"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        // The picker's definitions ride on the form: opening it fetched them.
        awaitState { it.recurringCosts.isNotEmpty() }
        viewModel.onRecurringCostChange(1)
        viewModel.onAmountChange("800.00")
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.id == 1 } }

        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals(1, create.recurring_cost_id)
        // The fake pays the cost's oldest Unpaid Occurrence at link time and
        // stores the pin on the row (web issue #57).
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals(1, saved.recurring_cost_id)
        assertEquals("2026-08-01", saved.occurrence_date)
    }

    @Test
    fun `switching a picked expense to income or transfer drops the link`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedRecurringCosts(recurringCost(1, "Rent"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        awaitState { it.recurringCosts.isNotEmpty() }
        viewModel.onRecurringCostChange(1)
        assertEquals(1, viewModel.uiState.value.modal?.recurringCostId)

        viewModel.onTypeChange(TransactionType.INCOME)
        assertNull(viewModel.uiState.value.modal?.recurringCostId)
        viewModel.onTypeChange(TransactionType.EXPENSE)
        viewModel.onRecurringCostChange(1)
        viewModel.onTypeChange(TransactionType.TRANSFER)
        assertNull(viewModel.uiState.value.modal?.recurringCostId)
    }

    @Test
    fun `an unlinked create never carries the recurring cost key`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.isNotEmpty() }

        val body = call("POST", "/api/transactions").body
        assertFalse(body.contains("recurring_cost_id"))
        val create = json.decodeFromString<TransactionCreateRequest>(body)
        assertNull(create.recurring_cost_id)
    }

    @Test
    fun `editing a linked expense without touching the picker leaves the link and the pin alone`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, recurringCostId = 1, occurrenceDate = "2026-08-01"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        // Editing a linked Expense seeds the pick with the stored link.
        assertEquals(1, viewModel.uiState.value.modal?.recurringCostId)
        viewModel.onAmountChange("850.00")
        viewModel.submit()
        awaitState { it.modal == null }

        // The link key is absent: a mere amount edit never reassigns the
        // Occurrence the link pays (web TransactionForm parity).
        val patch = call("PATCH", "/api/transactions/1")
        assertFalse(patch.body.contains("recurring_cost_id"))
        // The saved row lands through the write's own data-version refetch.
        awaitState {
            it.transactions.first { t -> t.id == 1 }.amount == "850.00"
        }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals(1, saved.recurring_cost_id)
        assertEquals("2026-08-01", saved.occurrence_date)
        assertEquals("850.00", saved.amount)
    }

    @Test
    fun `unlinking an expense sends an explicit null and frees the occurrence`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringCosts(recurringCost(1, "Rent"))
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, recurringCostId = 1, occurrenceDate = "2026-08-01"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onRecurringCostChange(null)
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/transactions/1")
        val update = json.decodeFromString<TransactionExpenseLinkUpdateRequest>(patch.body)
        assertNull(update.recurring_cost_id)
        assertTrue(patch.body.contains("\"recurring_cost_id\":null"))
        // Unlinking frees the Occurrence: the row's pin is cleared
        // (CONTEXT.md), landing through the write's own refetch.
        awaitState { it.transactions.first { t -> t.id == 1 }.recurring_cost_id == null }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertNull(saved.recurring_cost_id)
        assertNull(saved.occurrence_date)
    }

    @Test
    fun `relinking an expense pays the newly picked cost's oldest unpaid occurrence`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringCosts(
            recurringCost(1, "Rent", nextUnpaid = "2026-08-01"),
            recurringCost(2, "Gym", nextUnpaid = "2026-07-15"),
        )
        seedTransactions(
            transaction(1, TransactionType.EXPENSE, "800.00", "2026-08-01", walletId = 1, recurringCostId = 1, occurrenceDate = "2026-08-01"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onRecurringCostChange(2)
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/transactions/1")
        val update = json.decodeFromString<TransactionExpenseLinkUpdateRequest>(patch.body)
        assertEquals(2, update.recurring_cost_id)
        // The link pays the new cost's oldest Unpaid Occurrence at link
        // time; the row lands through the write's own refetch.
        awaitState { it.transactions.first { t -> t.id == 1 }.recurring_cost_id == 2 }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals(2, saved.recurring_cost_id)
        assertEquals("2026-07-15", saved.occurrence_date)
    }

    @Test
    fun `a definition created on the recurring tab reaches the open expense form`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        assertEquals(emptyList<RecurringCostDto>(), viewModel.uiState.value.recurringCosts)

        // A write elsewhere (the Recurring tab creating a definition) bumps
        // the data version; the background reload refreshes the picker too.
        seedRecurringCosts(recurringCost(1, "Rent"))
        DataVersion.bump()
        awaitState { it.recurringCosts.any { cost -> cost.name == "Rent" } }
    }

    // --- The Recurring Income link (web issue #61, ticket #23) ---

    @Test
    fun `creating a linked income sends the recurring income id and lands the pin`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringIncomes(recurringIncome(1, "Salary", nextUnpaid = "2026-08-01"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.INCOME)
        // The picker's definitions ride on the form: opening it fetched them.
        awaitState { it.recurringIncomes.isNotEmpty() }
        viewModel.onRecurringIncomeChange(1)
        viewModel.onAmountChange("2500.00")
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.any { t -> t.id == 1 } }

        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals(TransactionType.INCOME, create.type)
        assertEquals(1, create.recurring_income_id)
        assertNull(create.recurring_cost_id)
        // The fake pays the income's oldest Unpaid Occurrence at link time and
        // stores the pin on the row (web issue #61).
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals(1, saved.recurring_income_id)
        assertEquals("2026-08-01", saved.occurrence_date)
    }

    @Test
    fun `switching a picked income to expense or transfer drops the link`() = runBlocking {
        seedWallets(
            wallet(1, "Cash", WalletType.CASH, "100.00"),
            wallet(2, "Card", WalletType.CREDIT_CARD, "0.00"),
        )
        seedRecurringIncomes(recurringIncome(1, "Salary"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.INCOME)
        awaitState { it.recurringIncomes.isNotEmpty() }
        viewModel.onRecurringIncomeChange(1)
        assertEquals(1, viewModel.uiState.value.modal?.recurringIncomeId)

        viewModel.onTypeChange(TransactionType.EXPENSE)
        assertNull(viewModel.uiState.value.modal?.recurringIncomeId)
        viewModel.onTypeChange(TransactionType.INCOME)
        viewModel.onRecurringIncomeChange(1)
        viewModel.onTypeChange(TransactionType.TRANSFER)
        assertNull(viewModel.uiState.value.modal?.recurringIncomeId)
    }

    @Test
    fun `an unlinked create never carries the recurring income key`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.INCOME)
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal == null && it.transactions.isNotEmpty() }

        // An unlinked Income sends neither link key: the backend rejects the
        // cost key on an Income, and an absent income key means no link.
        val body = call("POST", "/api/transactions").body
        assertFalse(body.contains("recurring_income_id"))
        assertFalse(body.contains("recurring_cost_id"))
        val create = json.decodeFromString<TransactionCreateRequest>(body)
        assertNull(create.recurring_income_id)
        assertNull(create.recurring_cost_id)
    }

    @Test
    fun `editing a linked income without touching the picker leaves the link and the pin alone`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringIncomes(recurringIncome(1, "Salary"))
        seedTransactions(
            transaction(
                1, TransactionType.INCOME, "2500.00", "2026-08-01", walletId = 1,
                recurringIncomeId = 1, occurrenceDate = "2026-08-01",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        // Editing a linked Income seeds the pick with the stored link.
        assertEquals(1, viewModel.uiState.value.modal?.recurringIncomeId)
        viewModel.onAmountChange("2600.00")
        viewModel.submit()
        awaitState { it.modal == null }

        // The link key is absent: a mere amount edit never reassigns the
        // Occurrence the link pays (web TransactionForm parity).
        val patch = call("PATCH", "/api/transactions/1")
        assertFalse(patch.body.contains("recurring_income_id"))
        // The saved row lands through the write's own data-version refetch.
        awaitState {
            it.transactions.first { t -> t.id == 1 }.amount == "2600.00"
        }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals(1, saved.recurring_income_id)
        assertEquals("2026-08-01", saved.occurrence_date)
        assertEquals("2600.00", saved.amount)
    }

    @Test
    fun `unlinking an income sends an explicit null and frees the occurrence`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringIncomes(recurringIncome(1, "Salary"))
        seedTransactions(
            transaction(
                1, TransactionType.INCOME, "2500.00", "2026-08-01", walletId = 1,
                recurringIncomeId = 1, occurrenceDate = "2026-08-01",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onRecurringIncomeChange(null)
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/transactions/1")
        val update = json.decodeFromString<TransactionIncomeLinkUpdateRequest>(patch.body)
        assertNull(update.recurring_income_id)
        assertTrue(patch.body.contains("\"recurring_income_id\":null"))
        // Unlinking frees the Occurrence: the row's pin is cleared
        // (CONTEXT.md), landing through the write's own refetch.
        awaitState { it.transactions.first { t -> t.id == 1 }.recurring_income_id == null }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertNull(saved.recurring_income_id)
        assertNull(saved.occurrence_date)
    }

    @Test
    fun `relinking an income pays the newly picked income's oldest unpaid occurrence`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedRecurringIncomes(
            recurringIncome(1, "Salary", nextUnpaid = "2026-08-01"),
            recurringIncome(2, "Freelance", nextUnpaid = "2026-07-15"),
        )
        seedTransactions(
            transaction(
                1, TransactionType.INCOME, "2500.00", "2026-08-01", walletId = 1,
                recurringIncomeId = 1, occurrenceDate = "2026-08-01",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onRecurringIncomeChange(2)
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/transactions/1")
        val update = json.decodeFromString<TransactionIncomeLinkUpdateRequest>(patch.body)
        assertEquals(2, update.recurring_income_id)
        // The link pays the new income's oldest Unpaid Occurrence at link
        // time; the row lands through the write's own refetch.
        awaitState { it.transactions.first { t -> t.id == 1 }.recurring_income_id == 2 }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals(2, saved.recurring_income_id)
        assertEquals("2026-07-15", saved.occurrence_date)
    }

    @Test
    fun `a definition created on the recurring tab reaches the open income form`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onTypeChange(TransactionType.INCOME)
        assertEquals(emptyList<RecurringIncomeDto>(), viewModel.uiState.value.recurringIncomes)

        // A write elsewhere (the Recurring tab creating a definition) bumps
        // the data version; the background reload refreshes the picker too.
        seedRecurringIncomes(recurringIncome(1, "Salary"))
        DataVersion.bump()
        awaitState { it.recurringIncomes.any { income -> income.name == "Salary" } }
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

    // --- Location and Place (ticket #29) ---

    @Test
    fun `editing seeds the location and its Place from the row`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                latitude = "41.9028", longitude = "12.4964",
                placeName = "Esselunga", placeId = "ChIJabc",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })

        val modal = viewModel.uiState.value.modal
        assertEquals(LatLng(41.9028, 12.4964), modal?.location)
        assertEquals(Place("Esselunga", "ChIJabc"), modal?.place)
    }

    @Test
    fun `a located edit sends the four keys and a removed location clears them with explicit nulls`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                latitude = "41.9028", longitude = "12.4964",
                placeName = "Esselunga", placeId = "ChIJabc",
            ),
        )
        createViewModel()
        awaitLoaded()

        // An untouched save keeps the location: the patch carries the four
        // keys as values.
        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.submit()
        awaitState { it.modal == null }
        var patch = call("PATCH", "/api/transactions/1").body
        assertTrue(patch.contains("\"latitude\":\"41.9028\""))
        assertTrue(patch.contains("\"longitude\":\"12.4964\""))
        assertTrue(patch.contains("\"place_name\":\"Esselunga\""))
        assertTrue(patch.contains("\"place_id\":\"ChIJabc\""))
        assertEquals(
            transactionStore.first { it.id == 1 }.let { t ->
                Triple(t.latitude, t.longitude, t.place_id)
            },
            Triple("41.9028", "12.4964", "ChIJabc"),
        )

        // Removing the location clears the Place with it (ADR-0005): the
        // next patch carries explicit nulls — the backend applies a present
        // key even when null.
        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onRemoveLocation()
        viewModel.submit()
        awaitState { it.modal == null }
        patch = lastCall("PATCH", "/api/transactions/1").body
        assertTrue(patch.contains("\"latitude\":null"))
        assertTrue(patch.contains("\"longitude\":null"))
        assertTrue(patch.contains("\"place_name\":null"))
        assertTrue(patch.contains("\"place_id\":null"))
        assertNull(transactionStore.first { it.id == 1 }.latitude)
        assertNull(transactionStore.first { it.id == 1 }.place_name)
    }

    @Test
    fun `a map pick carrying a Place sets it and a coordinates-only pick clears it`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                latitude = "41.9028", longitude = "12.4964",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        // A search pick: name and place_id land together (ADR-0005).
        viewModel.onLocationPick(LatLng(41.9001, 12.5001), Place("Colosseo", "ChIJxyz"))
        viewModel.submit()
        awaitState { it.modal == null }
        var patch = call("PATCH", "/api/transactions/1").body
        assertTrue(patch.contains("\"latitude\":\"41.9001\""))
        assertTrue(patch.contains("\"place_name\":\"Colosseo\""))

        // A bare-map coordinates-only pick (the free picker's tap) clears
        // the stored Place: the name must always match the coordinates.
        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onLocationPick(LatLng(41.8999, 12.4999), null)
        viewModel.submit()
        awaitState { it.modal == null }
        patch = lastCall("PATCH", "/api/transactions/1").body
        assertTrue(patch.contains("\"latitude\":\"41.8999\""))
        assertTrue(patch.contains("\"place_name\":null"))
        assertTrue(patch.contains("\"place_id\":null"))
    }

    @Test
    fun `a create with a picked place carries the four keys`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.onLocationPick(LatLng(41.9028, 12.4964), Place("Esselunga", "ChIJabc"))
        viewModel.submit()
        awaitState { it.modal == null }

        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals("41.9028", create.latitude)
        assertEquals("12.4964", create.longitude)
        assertEquals("Esselunga", create.place_name)
        assertEquals("ChIJabc", create.place_id)
        // The write round-trips: the ledger's fresh row carries the keys.
        // The write bump's background refetch (ADR-0002) brings the row
        // into the held list with the location keys echoed.
        awaitState { it.transactions.any { t -> t.id == 1 } }
        val saved = viewModel.uiState.value.transactions.first { it.id == 1 }
        assertEquals("41.9028", saved.latitude)
        assertEquals("Esselunga", saved.place_name)
    }

    @Test
    fun `a place in the row without coordinates never reaches the wire`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        // A Place without coordinates is outside the model (CONTEXT.md); a
        // legacy row that somehow carries one is edited as locationless —
        // the form seeds the name but shows no location — and the wire
        // guard clears the place keys with the location's nulls.
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                placeName = "Ghost", placeId = "ChIJghost",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        assertNull(viewModel.uiState.value.modal?.location)
        assertEquals(Place("Ghost", "ChIJghost"), viewModel.uiState.value.modal?.place)
        viewModel.submit()
        awaitState { it.modal == null }
        val patch = call("PATCH", "/api/transactions/1").body
        assertTrue(patch.contains("\"latitude\":null"))
        assertTrue(patch.contains("\"place_name\":null"))
    }

    @Test
    fun `a locationless create never carries the location keys`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal == null }

        val body = call("POST", "/api/transactions").body
        assertFalse(body.contains("latitude"))
        assertFalse(body.contains("longitude"))
        assertFalse(body.contains("place_name"))
        assertFalse(body.contains("place_id"))
    }

    @Test
    fun `the gps prefill attaches the current position to a fresh create form`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = true
        location.position = LatLng(41.9028, 12.4964)
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        awaitState { it.modal?.location == LatLng(41.9028, 12.4964) }
    }

    @Test
    fun `use my location attaches the coordinates and clears a stored Place`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = true
        location.position = LatLng(41.9028, 12.4964)
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                latitude = "41.9001", longitude = "12.5001",
                placeName = "Colosseo", placeId = "ChIJxyz",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onUseMyLocation()
        awaitState {
            it.modal?.location == LatLng(41.9028, 12.4964) && it.modal?.locating == false
        }
        // GPS is coordinates-only (ADR-0005): the stored Place clears.
        assertNull(viewModel.uiState.value.modal?.place)
        assertNull(viewModel.uiState.value.modal?.gpsError)
        // A save now carries the GPS fix and no place keys.
        viewModel.submit()
        awaitState { it.modal == null }
        val patch = call("PATCH", "/api/transactions/1").body
        assertTrue(patch.contains("\"latitude\":\"41.9028\""))
        assertTrue(patch.contains("\"place_name\":null"))
    }

    @Test
    fun `a denied or failing gps pick shows the web's message and keeps the location`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = false
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onUseMyLocation()
        // The permission prompt goes up through the screen bridge; while it
        // is pending the button reads Locating… (locating=true).
        awaitState { it.modal?.requestingLocationPermission == true }
        assertTrue(viewModel.uiState.value.modal?.locating == true)
        viewModel.onLocationPermissionResult(false)
        awaitState { it.modal?.requestingLocationPermission == false }

        val modal = viewModel.uiState.value.modal
        assertNull(modal?.location)
        assertFalse(modal?.locating == true)
        assertEquals(TransactionsViewModel.GPS_ERROR_TEXT, modal?.gpsError)
    }

    @Test
    fun `a granted gps pick attaches the position without a second prompt`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = false
        location.position = LatLng(41.9, 12.4)
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onUseMyLocation()
        awaitState { it.modal?.requestingLocationPermission == true }
        viewModel.onLocationPermissionResult(true)
        awaitState { it.modal?.location == LatLng(41.9, 12.4) }
        assertNull(viewModel.uiState.value.modal?.gpsError)
        assertEquals(1, location.fetchCount)
    }

    @Test
    fun `the first save asks permission once and a denial saves without a location`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = false
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal?.requestingLocationPermission == true }
        // The modal is busy while the prompt is up: no second save can fire.
        assertTrue(viewModel.uiState.value.modal?.submitting == true)
        viewModel.onLocationPermissionResult(false)
        awaitState { it.modal == null }

        // A denial saves without a location — exactly the web's browser
        // prompt answered "no".
        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertNull(create.latitude)
        assertNull(create.longitude)
        assertEquals(0, location.fetchCount)
    }

    @Test
    fun `the first save attaches the position when the prompt is granted`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = false
        location.position = LatLng(41.9028, 12.4964)
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal?.requestingLocationPermission == true }
        viewModel.onLocationPermissionResult(true)
        awaitState { it.modal == null }

        val create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertEquals("41.9028", create.latitude)
        assertEquals("12.4964", create.longitude)
    }

    @Test
    fun `a removed location opts the session out of the prefill and the first-save prompt`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = true
        location.position = LatLng(41.9028, 12.4964)
        createViewModel()
        awaitLoaded()

        // The prefill lands, the user removes it: the save must not
        // re-attach a position the user opted out of.
        viewModel.openCreate()
        awaitState { it.modal?.location == LatLng(41.9028, 12.4964) }
        viewModel.onRemoveLocation()
        assertEquals(1, location.fetchCount)
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal == null }
        var create = json.decodeFromString<TransactionCreateRequest>(call("POST", "/api/transactions").body)
        assertNull(create.latitude)
        // The opt-out outlives the form: the next create form opens with no
        // prefill and its save never prompts either.
        assertEquals(1, location.fetchCount)
        viewModel.openCreate()
        assertNull(viewModel.uiState.value.modal?.location)
        viewModel.onAmountChange("5.00")
        viewModel.submit()
        awaitState { it.modal == null }
        create = json.decodeFromString<TransactionCreateRequest>(
            calls.toList().last { it.method == "POST" && it.path == "/api/transactions" }.body,
        )
        assertNull(create.latitude)
        assertEquals(1, location.fetchCount)
    }

    @Test
    fun `an edit never prefills and never asks on save`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = true
        location.position = LatLng(41.9028, 12.4964)
        seedTransactions(transaction(1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        assertNull(viewModel.uiState.value.modal?.location)
        viewModel.onAmountChange("6.00")
        viewModel.submit()
        awaitState { it.modal == null }
        // No GPS fetch happened at all — location keys clear explicitly.
        assertEquals(0, location.fetchCount)
        assertTrue(call("PATCH", "/api/transactions/1").body.contains("\"latitude\":null"))
    }

    @Test
    fun `removing a location on an edit does not opt the session out`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        location.granted = true
        location.position = LatLng(41.9028, 12.4964)
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                latitude = "41.9001", longitude = "12.5001",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onRemoveLocation()
        viewModel.closeModal()
        // A fresh create form still prefills: the edit's removal was a
        // one-off decision, not a session opt-out (web issue #25).
        viewModel.openCreate()
        awaitState { it.modal?.location == LatLng(41.9028, 12.4964) }
    }

    @Test
    fun `the picker opens and cancels without touching the location`() = runBlocking {
        seedWallets(wallet(1, "Cash", WalletType.CASH, "100.00"))
        seedTransactions(
            transaction(
                1, TransactionType.EXPENSE, "5.00", "2026-08-01", walletId = 1,
                latitude = "41.9001", longitude = "12.5001",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.transactions.first { it.id == 1 })
        viewModel.onOpenLocationPicker()
        assertTrue(viewModel.uiState.value.modal?.showingPicker == true)
        viewModel.onLocationPickerCancel()
        val modal = viewModel.uiState.value.modal
        assertFalse(modal?.showingPicker == true)
        assertEquals(LatLng(41.9001, 12.5001), modal?.location)
    }

    /** A stub quintuple gateway for the pure-timing debounce test: the seam
     * tests drive the real repositories; this one only counts fetches. */
    private class RecordingGateway : TransactionGateway, WalletGateway, CategoryGateway,
        RecurringCostGateway, RecurringIncomeGateway {
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

        override suspend fun fetchRecurringCosts(): List<RecurringCostDto> = emptyList()

        override suspend fun fetchRecurringIncomes(): List<RecurringIncomeDto> = emptyList()

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

        override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto =
            error("unused in the debounce test")

        override suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto =
            error("unused in the debounce test")

        override suspend fun deleteRecurringCost(id: Int) = error("unused in the debounce test")

        override suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused in the debounce test")

        override suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused in the debounce test")

        override suspend fun deleteRecurringIncome(id: Int) = error("unused in the debounce test")

        override suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto> =
            error("unused in the debounce test")

        override suspend fun setOccurrenceSkipped(
            id: Int,
            occurrenceDate: String,
            skipped: Boolean,
        ): List<RecurringOccurrenceDto> = error("unused in the debounce test")

        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto =
            error("unused in the debounce test")

        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
            error("unused in the debounce test")

        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto =
            error("unused in the debounce test")

        override suspend fun export(filters: TransactionFilters): ExportFile =
            error("unused in the debounce test")
    }
}
