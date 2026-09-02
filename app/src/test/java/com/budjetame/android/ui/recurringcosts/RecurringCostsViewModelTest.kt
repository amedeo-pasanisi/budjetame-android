package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringCostCreateRequest
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringCostUpdateRequest
import com.budjetame.android.data.recurringcost.ApiRecurringCostRepository
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
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The Recurring Costs flow tested at the single seam (the HTTP API): the
 * ViewModel is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of the
 * /recurring-costs resource — the list with the derived dates, and the
 * create/PATCH/delete writes with the backend's duplicate-name rule (names
 * unique per Account, case-insensitively) and the web's exact 409 message.
 * Request bodies are captured for assertions.
 */
class RecurringCostsViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: RecurringCostsViewModel

    private val store = mutableListOf<RecurringCostDto>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var nextId = 1
    private var listStatus = 200
    private var createStatus = 201
    private var updateStatus = 200
    private var deleteStatus = 204

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
        updateStatus = 200
        deleteStatus = 204
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
        val repository = ApiRecurringCostRepository(client.create(RecurringCostApi::class.java))
        viewModel = RecurringCostsViewModel(repository)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))

        return when {
            method == "GET" && path == "/api/recurring-costs" -> when {
                listStatus != 200 -> jsonResponse(listStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(store))
            }

            method == "POST" && path == "/api/recurring-costs" -> when {
                createStatus != 201 -> jsonResponse(
                    createStatus,
                    """{"detail":"A Recurring Cost with this name already exists"}""",
                )
                else -> {
                    val create = json.decodeFromString<RecurringCostCreateRequest>(body)
                    if (store.any { it.name.equals(create.name, ignoreCase = true) }) {
                        return jsonResponse(
                            409,
                            """{"detail":"A Recurring Cost with this name already exists"}""",
                        )
                    }
                    val cost = costDto(
                        id = nextId++,
                        name = create.name,
                        amount = create.amount,
                        intervalValue = create.interval_value,
                        intervalUnit = create.interval_unit,
                        startDate = create.start_date,
                        dueDay = create.due_day,
                        dueMonth = create.due_month,
                    )
                    store.add(cost)
                    jsonResponse(201, json.encodeToString(cost))
                }
            }

            method == "PATCH" && path.matches(Regex("/api/recurring-costs/\\d+")) -> {
                val id = path.removePrefix("/api/recurring-costs/").toInt()
                when {
                    updateStatus != 200 -> jsonResponse(
                        updateStatus,
                        """{"detail":"A Recurring Cost with this name already exists"}""",
                    )
                    else -> {
                        val update = json.decodeFromString<RecurringCostUpdateRequest>(body)
                        val index = store.indexOfFirst { it.id == id }
                        if (index < 0) return jsonResponse(403, """{"detail":"Recurring Cost not found"}""")
                        val existing = store[index]
                        if (store.any {
                                it.id != id && it.name.equals(update.name, ignoreCase = true)
                            }
                        ) {
                            return jsonResponse(
                                409,
                                """{"detail":"A Recurring Cost with this name already exists"}""",
                            )
                        }
                        val updated = existing.copy(
                            name = update.name,
                            amount = update.amount,
                            interval_value = update.interval_value,
                            interval_unit = update.interval_unit,
                            start_date = update.start_date,
                            due_day = update.due_day,
                            due_month = update.due_month,
                        )
                        store[index] = updated
                        jsonResponse(200, json.encodeToString(updated))
                    }
                }
            }

            method == "DELETE" && path.matches(Regex("/api/recurring-costs/\\d+")) -> {
                val id = path.removePrefix("/api/recurring-costs/").toInt()
                store.removeAll { it.id == id }
                MockResponse().setResponseCode(deleteStatus)
            }

            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun seed(vararg costs: RecurringCostDto) {
        store.addAll(costs)
        costs.forEach { nextId = maxOf(nextId, it.id + 1) }
    }

    private fun costDto(
        id: Int,
        name: String,
        amount: String = "10.00",
        intervalValue: Int = 1,
        intervalUnit: IntervalUnit = IntervalUnit.MONTHS,
        startDate: String? = null,
        dueDay: Int? = null,
        dueMonth: Int? = null,
        nextDue: String = "2026-09-05",
        nextUnpaid: String = "2026-09-05",
        backlog: Int = 0,
        overdue: Boolean = false,
    ) = RecurringCostDto(
        id = id,
        name = name,
        amount = amount,
        interval_value = intervalValue,
        interval_unit = intervalUnit,
        start_date = startDate,
        due_day = dueDay,
        due_month = dueMonth,
        next_due_date = nextDue,
        next_unpaid_occurrence_date = nextUnpaid,
        backlog_count = backlog,
        overdue = overdue,
        created_at = "2026-08-01T10:00:00Z",
    )

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (RecurringCostsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

    // --- Load: the next-due order with the derived state intact ---

    @Test
    fun `the list loads ordered by next due date with the backlog and overdue state`() = runBlocking {
        seed(
            // Deliberately out of next-due order: the screen renders sorted.
            costDto(1, "Rent", amount = "800.00", nextDue = "2026-09-01", nextUnpaid = "2026-09-01"),
            costDto(2, "Netflix", amount = "9.99", nextDue = "2026-08-15", nextUnpaid = "2026-08-01", backlog = 1, overdue = true),
        )
        createViewModel()
        awaitLoaded()

        val costs = viewModel.uiState.value.costs
        assertEquals(listOf(2, 1), costs.map { it.id })
        val netflix = costs.first { it.id == 2 }
        assertEquals(1, netflix.backlog_count)
        assertTrue(netflix.overdue)
        // The row's data is the API's derived state, never computed locally.
        assertEquals("2026-08-15", netflix.next_due_date)
        assertEquals("2026-08-01", netflix.next_unpaid_occurrence_date)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `a write elsewhere refetches the list in the background`() = runBlocking {
        seed(costDto(1, "Rent", nextDue = "2026-09-01", backlog = 1, overdue = true))
        createViewModel()
        awaitLoaded()
        assertEquals(1, viewModel.uiState.value.costs.size)

        // A link paid on the Transactions tab bumps the version; the list's
        // derived state re-renders from the fresh API response (ADR-0002).
        store[0] = store[0].copy(
            backlog_count = 0,
            overdue = false,
            next_unpaid_occurrence_date = "2026-10-01",
        )
        DataVersion.bump()
        awaitState { it.costs.first().next_unpaid_occurrence_date == "2026-10-01" }
        val refreshed = viewModel.uiState.value.costs.first()
        assertEquals(0, refreshed.backlog_count)
        assertFalse(refreshed.overdue)
    }

    // --- Create ---

    @Test
    fun `create defaults the draft to a monthly definition and sends it whole`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        val modal = viewModel.uiState.value.modal!!
        // The web form's defaults: monthly, every 1, no dates, no override.
        assertEquals(IntervalUnit.MONTHS, modal.intervalUnit)
        assertEquals("1", modal.intervalValue)
        assertEquals("", modal.startDate)
        assertNull(modal.dueDay)
        assertNull(modal.dueMonth)
        assertFalse(modal.editing)

        viewModel.onNameChange("Rent")
        viewModel.onAmountChange("800.00")
        viewModel.onStartDateChange("2026-08-01")
        viewModel.onDueDayChange(15)
        viewModel.submit()
        awaitState { it.modal == null && it.costs.any { c -> c.name == "Rent" } }

        val create = json.decodeFromString<RecurringCostCreateRequest>(call("POST", "/api/recurring-costs").body)
        assertEquals("Rent", create.name)
        assertEquals("800.00", create.amount)
        assertEquals(1, create.interval_value)
        assertEquals(IntervalUnit.MONTHS, create.interval_unit)
        assertEquals("2026-08-01", create.start_date)
        assertEquals(15, create.due_day)
        assertNull(create.due_month)
        assertEquals(listOf("Rent"), viewModel.uiState.value.costs.map { it.name })
    }

    @Test
    fun `an unset start date and override stay off the create wire`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Rent")
        viewModel.onAmountChange("800.00")
        viewModel.submit()
        awaitState { it.modal == null }

        val body = call("POST", "/api/recurring-costs").body
        assertFalse(body.contains("start_date"))
        assertFalse(body.contains("due_day"))
        assertFalse(body.contains("due_month"))
        val create = json.decodeFromString<RecurringCostCreateRequest>(body)
        assertNull(create.start_date)
        assertNull(create.due_day)
        assertNull(create.due_month)
    }

    @Test
    fun `a year interval sends the month and day pair`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Car tax")
        viewModel.onAmountChange("200.00")
        viewModel.onIntervalUnitChange(IntervalUnit.YEARS)
        viewModel.onDueMonthChange(6)
        viewModel.onDueDayChange(30)
        viewModel.submit()
        awaitState { it.modal == null }

        val create = json.decodeFromString<RecurringCostCreateRequest>(call("POST", "/api/recurring-costs").body)
        assertEquals(IntervalUnit.YEARS, create.interval_unit)
        assertEquals(6, create.due_month)
        assertEquals(30, create.due_day)
    }

    @Test
    fun `a half-picked year pair never submits`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Car tax")
        viewModel.onAmountChange("200.00")
        viewModel.onIntervalUnitChange(IntervalUnit.YEARS)
        viewModel.onDueDayChange(30)
        assertFalse(viewModel.uiState.value.modal!!.canSubmit)
        viewModel.submit()

        assertTrue(calls.toList().none { it.method == "POST" })
        assertTrue(viewModel.uiState.value.modal != null)
    }

    @Test
    fun `switching a month's due day to a day interval drops the stale override`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Coffee")
        viewModel.onAmountChange("30.00")
        viewModel.onIntervalUnitChange(IntervalUnit.MONTHS)
        viewModel.onDueDayChange(15)
        viewModel.onIntervalUnitChange(IntervalUnit.DAYS)
        viewModel.submit()
        awaitState { it.modal == null }

        val create = json.decodeFromString<RecurringCostCreateRequest>(call("POST", "/api/recurring-costs").body)
        assertEquals(IntervalUnit.DAYS, create.interval_unit)
        assertNull(create.due_day)
        assertNull(create.due_month)
    }

    @Test
    fun `the blank and invalid gates never submit`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        // Blank name.
        viewModel.onAmountChange("5.00")
        assertFalse(viewModel.uiState.value.modal!!.canSubmit)
        viewModel.submit()
        // Non-numeric amount.
        viewModel.onNameChange("Rent")
        viewModel.onAmountChange("abc")
        assertFalse(viewModel.uiState.value.modal!!.canSubmit)
        viewModel.submit()
        // Interval below one.
        viewModel.onAmountChange("5.00")
        viewModel.onIntervalValueChange("0")
        assertFalse(viewModel.uiState.value.modal!!.canSubmit)
        viewModel.submit()

        assertTrue(calls.toList().none { it.method == "POST" })
    }

    @Test
    fun `a duplicate name shows the web's exact message`() = runBlocking {
        seed(costDto(1, "Rent"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("rent")
        viewModel.onAmountChange("800.00")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals(
            "A recurring cost with this name already exists.",
            viewModel.uiState.value.modal?.error,
        )
        assertEquals(1, viewModel.uiState.value.costs.size)
    }

    @Test
    fun `a 422 shows the check-your-fields message`() = runBlocking {
        createViewModel()
        awaitLoaded()

        createStatus = 422
        viewModel.openCreate()
        viewModel.onNameChange("Rent")
        viewModel.onAmountChange("800.00")
        viewModel.submit()
        awaitState { it.modal?.error == "Check the fields and try again." }
    }

    // --- Edit ---

    @Test
    fun `edit prefills the definition and the patch sends the whole form with explicit nulls`() = runBlocking {
        seed(
            costDto(
                1, "Rent", amount = "800.00", startDate = "2026-01-01", dueDay = 15,
                nextDue = "2026-09-15", nextUnpaid = "2026-09-15",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        val modal = viewModel.uiState.value.modal!!
        assertTrue(modal.editing)
        assertEquals("Rent", modal.name)
        assertEquals("800.00", modal.amount)
        assertEquals("1", modal.intervalValue)
        assertEquals(IntervalUnit.MONTHS, modal.intervalUnit)
        assertEquals("2026-01-01", modal.startDate)
        assertEquals(15, modal.dueDay)
        assertNull(modal.dueMonth)

        // Clearing the start date travels as an explicit null: the PATCH
        // field present is applied even when null (the unset = creation
        // date semantics are the backend's).
        viewModel.onStartDateChange("")
        viewModel.onNameChange("Rent (home)")
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/recurring-costs/1")
        assertTrue(patch.body.contains("\"start_date\":null"))
        val update = json.decodeFromString<RecurringCostUpdateRequest>(patch.body)
        assertEquals("Rent (home)", update.name)
        assertEquals("800.00", update.amount)
        assertEquals(15, update.due_day)
        assertNull(update.start_date)
        // The row landed, re-sorted in place.
        assertEquals("Rent (home)", viewModel.uiState.value.costs.first().name)
    }

    @Test
    fun `an edit renaming onto another cost shows the web's exact message`() = runBlocking {
        seed(costDto(1, "Rent"), costDto(2, "Netflix"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 2 })
        viewModel.onNameChange("RENT")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals(
            "A recurring cost with this name already exists.",
            viewModel.uiState.value.modal?.error,
        )
        assertEquals("Netflix", viewModel.uiState.value.costs.first { it.id == 2 }.name)
    }

    // --- Delete ---

    @Test
    fun `delete is tap-again confirmed and removes the row`() = runBlocking {
        seed(costDto(1, "Rent"), costDto(2, "Netflix"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        viewModel.onDeleteTap()
        assertTrue(viewModel.uiState.value.modal!!.confirmingDelete)
        viewModel.onDeleteTap()
        awaitState { it.modal == null && it.costs.none { c -> c.id == 1 } }

        assertTrue(calls.toList().any { it.method == "DELETE" && it.path == "/api/recurring-costs/1" })
        assertEquals(listOf(2), viewModel.uiState.value.costs.map { it.id })
    }

    @Test
    fun `a failed delete keeps the modal with the error`() = runBlocking {
        seed(costDto(1, "Rent"))
        createViewModel()
        awaitLoaded()

        deleteStatus = 500
        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        viewModel.onDeleteTap()
        viewModel.onDeleteTap()
        awaitState { it.modal?.error != null }

        assertEquals("Could not delete the recurring cost.", viewModel.uiState.value.modal?.error)
        assertEquals(1, viewModel.uiState.value.costs.size)
        assertEquals(listOf(1), viewModel.uiState.value.costs.map { it.id })
    }

    // --- Load error + retry ---

    @Test
    fun `a load failure shows the error and retry refetches`() = runBlocking {
        listStatus = 500
        createViewModel()
        awaitLoaded()
        assertEquals("Could not load your recurring costs.", viewModel.uiState.value.loadError)

        listStatus = 200
        seed(costDto(1, "Rent"))
        viewModel.retry()
        awaitState { !it.loading && it.costs.isNotEmpty() }

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("Rent", viewModel.uiState.value.costs.first().name)
    }
}
