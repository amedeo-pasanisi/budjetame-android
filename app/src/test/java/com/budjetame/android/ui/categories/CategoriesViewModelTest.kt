package com.budjetame.android.ui.categories

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.CategoryCreateRequest
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryMergeRequest
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.CategoryUpdateRequest
import com.budjetame.android.data.category.ApiCategoryRepository
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
 * The Categories flow tested at the single seam (the HTTP API): the
 * ViewModel is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of the
 * /categories resource — with per-Category Transaction counts so the merge
 * offer (ADR-0007) carries a believable number and the delete test can
 * assert no Transaction is ever deleted. Request bodies are captured for
 * assertions.
 */
class CategoriesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: CategoriesViewModel

    private val store = mutableListOf<CategoryDto>()
    private val transactionCounts = mutableMapOf<Int, Int>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var nextId = 1
    private var totalTransactions = 0
    private var uncategorizedTransactions = 0
    private var listStatus = 200
    private var createStatus = 201
    private var updateStatus = 200

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        store.clear()
        transactionCounts.clear()
        calls.clear()
        nextId = 1
        totalTransactions = 0
        uncategorizedTransactions = 0
        listStatus = 200
        createStatus = 201
        updateStatus = 200
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
        val repository = ApiCategoryRepository(client.create(CategoryApi::class.java))
        viewModel = CategoriesViewModel(repository)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))

        return when {
            method == "GET" && path == "/api/categories" -> when {
                listStatus != 200 -> jsonResponse(listStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(store))
            }

            method == "POST" && path == "/api/categories" -> when {
                createStatus != 201 ->
                    jsonResponse(createStatus, """{"detail":"A Category with this name already exists"}""")
                else -> {
                    val create = json.decodeFromString<CategoryCreateRequest>(body)
                    val category = CategoryDto(
                        id = nextId++,
                        name = create.name,
                        type = create.type,
                        icon = create.icon.ifEmpty { null },
                        color = create.color,
                        created_at = "2026-08-01T10:00:00Z",
                    )
                    store.add(category)
                    jsonResponse(201, json.encodeToString(category))
                }
            }

            method == "PATCH" && path.matches(Regex("/api/categories/\\d+")) -> {
                val id = path.removePrefix("/api/categories/").toInt()
                when {
                    // The forced path stands in for the unique-index race: a
                    // plain-string 409, not the structured merge offer.
                    updateStatus != 200 ->
                        jsonResponse(updateStatus, """{"detail":"A Category with this name already exists"}""")
                    else -> {
                        val update = json.decodeFromString<CategoryUpdateRequest>(body)
                        val index = store.indexOfFirst { it.id == id }
                        val existing = store[index]
                        val target = store.firstOrNull {
                            it.id != id && it.type == existing.type &&
                                it.name.equals(update.name, ignoreCase = true)
                        }
                        if (target != null) {
                            // The rename collides: the merge offer (ADR-0007),
                            // writing nothing.
                            jsonResponse(
                                409,
                                """{"detail":{"message":"A Category with this name already exists","target_id":${target.id},"transaction_count":${transactionCounts[id] ?: 0}}}""",
                            )
                        } else {
                            val updated = existing.copy(
                                name = update.name,
                                icon = update.icon.ifEmpty { null },
                                color = update.color,
                            )
                            store[index] = updated
                            jsonResponse(200, json.encodeToString(updated))
                        }
                    }
                }
            }

            method == "POST" && path.matches(Regex("/api/categories/\\d+/merge")) -> {
                val id = path.removePrefix("/api/categories/").removeSuffix("/merge").toInt()
                val merge = json.decodeFromString<CategoryMergeRequest>(body)
                val target = store.first { it.id == merge.target_id }
                transactionCounts[merge.target_id] =
                    (transactionCounts[merge.target_id] ?: 0) + (transactionCounts[id] ?: 0)
                transactionCounts.remove(id)
                store.removeAll { it.id == id }
                jsonResponse(200, json.encodeToString(target))
            }

            // Delete uncategorizes: the category goes, its Transactions
            // stay and simply no longer carry a category id.
            method == "DELETE" && path.matches(Regex("/api/categories/\\d+")) -> {
                val id = path.removePrefix("/api/categories/").toInt()
                store.removeAll { it.id == id }
                uncategorizedTransactions += transactionCounts.remove(id) ?: 0
                MockResponse().setResponseCode(204)
            }

            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun seed(vararg categories: CategoryDto) {
        store.addAll(categories)
        categories.forEach { nextId = maxOf(nextId, it.id + 1) }
    }

    /** Attach Transactions to a Category — the rows a merge would move. */
    private fun seedTransactions(categoryId: Int, count: Int) {
        transactionCounts[categoryId] = count
        totalTransactions += count
    }

    private fun category(
        id: Int,
        name: String,
        type: CategoryType,
        icon: String? = null,
        color: String = "#ef4444",
    ) = CategoryDto(id, name, type, icon, color, "2026-08-01T10:00:00Z")

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (CategoriesViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

    // --- Load: sections, sorting, search ---

    @Test
    fun `categories group into expenses and incomes sorted case-insensitively`() = runBlocking {
        seed(
            category(1, "banana", CategoryType.EXPENSE, "🍌", "#f59e0b"),
            category(2, "Salary", CategoryType.INCOME, "💼", "#3b82f6"),
            category(3, "apple", CategoryType.EXPENSE, "🍎", "#ef4444"),
            category(4, "freelance", CategoryType.INCOME, null, "#6366f1"),
            category(5, "Carrots", CategoryType.EXPENSE, null, "#84cc16"),
        )
        createViewModel()
        awaitLoaded()

        val state = viewModel.uiState.value
        assertEquals(
            listOf(CategoryType.EXPENSE, CategoryType.INCOME),
            state.sections.map { it.type },
        )
        assertEquals(listOf("Expenses", "Incomes"), state.sections.map { it.label })
        assertEquals(listOf("apple", "banana", "Carrots"), state.sections[0].items.map { it.name })
        assertEquals(listOf("freelance", "Salary"), state.sections[1].items.map { it.name })
    }

    @Test
    fun `the search needle filters both sections live and clearing restores`() = runBlocking {
        seed(
            category(1, "apple", CategoryType.EXPENSE),
            category(2, "Salary", CategoryType.INCOME),
            category(3, "groceries", CategoryType.EXPENSE),
        )
        createViewModel()
        awaitLoaded()

        viewModel.onQueryChange("a")
        awaitState { it.sections.sumOf { section -> section.items.size } == 2 }
        assertEquals(listOf("apple"), viewModel.uiState.value.sections[0].items.map { it.name })
        assertEquals(listOf("Salary"), viewModel.uiState.value.sections[1].items.map { it.name })

        viewModel.onQueryChange("nothing matches")
        awaitState { it.sections.all { section -> section.items.isEmpty() } }

        viewModel.onQueryChange("")
        awaitState { it.sections.sumOf { section -> section.items.size } == 3 }
    }

    // --- Create ---

    @Test
    fun `create sends the name type icon and color and lands the category`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Groceries")
        viewModel.onTypeChange(CategoryType.EXPENSE)
        viewModel.onIconChange("🛒")
        viewModel.onColorChange("#10b981")
        viewModel.submit()
        awaitState { it.modal == null && it.categories.any { category -> category.name == "Groceries" } }

        val create = json.decodeFromString<CategoryCreateRequest>(call("POST", "/api/categories").body)
        assertEquals("Groceries", create.name)
        assertEquals(CategoryType.EXPENSE, create.type)
        assertEquals("🛒", create.icon)
        assertEquals("#10b981", create.color)
    }

    @Test
    fun `create defaults the type to expense and the color to the first preset`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Lunch")
        viewModel.submit()
        awaitState { it.modal == null && it.categories.any { category -> category.name == "Lunch" } }

        val create = json.decodeFromString<CategoryCreateRequest>(call("POST", "/api/categories").body)
        assertEquals(CategoryType.EXPENSE, create.type)
        assertEquals("#ef4444", create.color)
        assertEquals("", create.icon)
    }

    @Test
    fun `create conflict shows the web's exact message`() = runBlocking {
        createStatus = 409
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Taken")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals("A category with this name already exists.", viewModel.uiState.value.modal?.error)
    }

    // --- Edit ---

    @Test
    fun `edit sends the name icon and color and keeps the type`() = runBlocking {
        seed(category(1, "Groceries", CategoryType.EXPENSE, "🛒", "#10b981"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.categories.first())
        viewModel.onNameChange("Food")
        viewModel.onIconChange("")
        viewModel.onColorChange("#ef4444")
        viewModel.submit()
        awaitState { it.modal == null && it.categories.any { category -> category.name == "Food" } }

        val patch = call("PATCH", "/api/categories/1")
        assertFalse(patch.body.contains("type"))
        val update = json.decodeFromString<CategoryUpdateRequest>(patch.body)
        assertEquals("Food", update.name)
        assertEquals("", update.icon)
        assertEquals("#ef4444", update.color)
        val saved = viewModel.uiState.value.categories.first { it.id == 1 }
        assertEquals(CategoryType.EXPENSE, saved.type)
        assertNull(saved.icon)
    }

    @Test
    fun `a blank name never submits`() = runBlocking {
        seed(category(1, "Groceries", CategoryType.EXPENSE))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("   ")
        viewModel.submit()

        assertTrue(calls.toList().none { it.method == "POST" && it.path == "/api/categories" })
        assertTrue(viewModel.uiState.value.modal?.canSubmit == false)
    }

    // --- Merge (ADR-0007) ---

    @Test
    fun `a colliding rename parses the structured 409 and offers the merge with the transaction count`() = runBlocking {
        seed(category(1, "Groceries", CategoryType.EXPENSE, "🛒"), category(2, "Food", CategoryType.EXPENSE, "🍽"))
        seedTransactions(1, 3)
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.categories.first { it.id == 1 })
        viewModel.onNameChange("FOOD")
        viewModel.submit()
        awaitState { it.modal?.mergeOffer != null }

        val offer = viewModel.uiState.value.modal?.mergeOffer
        assertEquals(2, offer?.targetId)
        assertEquals(3, offer?.transactionCount)
        // The offer writes nothing: no merge call has been made.
        assertTrue(calls.toList().none { it.method == "POST" && it.path.endsWith("/merge") })
        // The rename itself wrote nothing either (the fake leaves the store).
        assertEquals(listOf("Groceries", "Food"), viewModel.uiState.value.categories.map { it.name })
    }

    @Test
    fun `the confirmed merge posts the target and lands the survivor with the transactions moved`() = runBlocking {
        seed(category(1, "Groceries", CategoryType.EXPENSE, "🛒"), category(2, "Food", CategoryType.EXPENSE, "🍽"))
        seedTransactions(1, 3)
        seedTransactions(2, 7)
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.categories.first { it.id == 1 })
        viewModel.onNameChange("FOOD")
        viewModel.submit()
        awaitState { it.modal?.mergeOffer != null }

        viewModel.onMergeTap()
        assertTrue(viewModel.uiState.value.modal!!.confirmingMerge)
        viewModel.onMergeTap()
        awaitState { it.modal == null && it.categories.none { category -> category.id == 1 } }

        val merge = json.decodeFromString<CategoryMergeRequest>(call("POST", "/api/categories/1/merge").body)
        assertEquals(2, merge.target_id)
        assertEquals(listOf(2), viewModel.uiState.value.categories.map { it.id })
        assertEquals(10, transactionCounts[2])
        // Every Transaction moved to the survivor; none vanished and none
        // became uncategorized.
        assertEquals(0, uncategorizedTransactions)
        assertEquals(totalTransactions, transactionCounts.values.sum())
    }

    @Test
    fun `cancel merge clears the offer without calling the api`() = runBlocking {
        seed(category(1, "Groceries", CategoryType.EXPENSE), category(2, "Food", CategoryType.EXPENSE))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.categories.first { it.id == 1 })
        viewModel.onNameChange("FOOD")
        viewModel.submit()
        awaitState { it.modal?.mergeOffer != null }

        viewModel.cancelMerge()
        assertNull(viewModel.uiState.value.modal?.mergeOffer)
        assertTrue(calls.toList().none { it.method == "POST" && it.path.endsWith("/merge") })
    }

    @Test
    fun `a plain-string conflict 409 shows the error not an offer`() = runBlocking {
        updateStatus = 409
        seed(category(1, "Mine", CategoryType.EXPENSE), category(2, "Taken", CategoryType.EXPENSE))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.categories.first { it.id == 1 })
        viewModel.onNameChange("TAKEN")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals("A category with this name already exists.", viewModel.uiState.value.modal?.error)
        assertNull(viewModel.uiState.value.modal?.mergeOffer)
    }

    // --- Delete ---

    @Test
    fun `delete is tap-again confirmed and no transaction is ever deleted`() = runBlocking {
        seed(category(1, "Groceries", CategoryType.EXPENSE), category(2, "Food", CategoryType.EXPENSE))
        seedTransactions(1, 3)
        seedTransactions(2, 7)
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.categories.first { it.id == 1 })
        viewModel.onDeleteTap()
        assertTrue(viewModel.uiState.value.modal!!.confirmingDelete)
        viewModel.onDeleteTap()
        awaitState { it.modal == null && it.categories.none { category -> category.id == 1 } }

        assertTrue(calls.toList().any { it.method == "DELETE" && it.path == "/api/categories/1" })
        // The other Category survives and no Transaction was deleted: the
        // client sent no Transaction request, the deleted Category's rows
        // became uncategorized, and the totals still add up.
        assertEquals(listOf(2), viewModel.uiState.value.categories.map { it.id })
        assertEquals(7, transactionCounts[2])
        assertEquals(3, uncategorizedTransactions)
        assertEquals(totalTransactions, uncategorizedTransactions + transactionCounts.values.sum())
        assertTrue(calls.toList().none { it.path.startsWith("/api/transactions") })
    }

    // --- Load error + retry ---

    @Test
    fun `a load failure shows the error and retry refetches`() = runBlocking {
        listStatus = 500
        createViewModel()
        awaitLoaded()
        assertEquals("Could not load your categories.", viewModel.uiState.value.loadError)

        listStatus = 200
        seed(category(1, "Food", CategoryType.EXPENSE))
        viewModel.retry()
        awaitState { !it.loading && it.categories.isNotEmpty() }

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("Food", viewModel.uiState.value.categories.first().name)
    }
}
