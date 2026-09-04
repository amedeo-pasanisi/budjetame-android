package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringCostCreateRequest
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringCostUpdateRequest
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.data.api.RecurringOccurrenceUpdateRequest
import com.budjetame.android.data.recurringcost.ApiRecurringCostRepository
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/** The fixture creation day — the date the fake's create materializes for
 * an empty start date (ADR-0024). */
private const val CREATION_DAY = "2026-08-01"

/**
 * The Recurring Costs flow tested at the single seam (the HTTP API): the
 * ViewModel is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of the
 * /recurring-costs resource — the list with the derived dates, the
 * create/PATCH/delete writes with the backend's duplicate-name rule (names
 * unique per Account, case-insensitively) and the web's exact 409 message,
 * and the Occurrences read with its per-Occurrence skip write (web
 * ADR-0026): the read answers the section's rows from a per-definition
 * store, and a PUT flips the row's skipped state in that store — the
 * write's own data-version bump then refetches the list, so a test can
 * re-derive the definition's state behind the modal exactly like the
 * backend would. Request bodies are captured for assertions.
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

    /** A raw list body served verbatim (when non-null): lets a test send a
     * definition JSON that the fixtures cannot produce — e.g. one still
     * carrying the backend's derived `overdue` field (web ADR-0025) or the
     * gone `next_skip_action` (web ADR-0026). */
    private var rawListBody: String? = null

    /** The Occurrences section's rows per definition (web ADR-0026): the
     * read answers the store verbatim — the server's order is
     * authoritative — and a skip write flips the row's own state in it,
     * exactly the backend's idempotent per-date write. */
    private val occurrenceStore = mutableMapOf<Int, MutableList<RecurringOccurrenceDto>>()
    private var occurrencesStatus = 200
    private var occurrencePutStatus = 200

    /** A test hook run by the fake after a successful per-Occurrence skip
     * write: lets a test re-derive the definition's state in the store
     * (badge, next due) the way the real backend recomputes it from the
     * stored skips — the write's data-version bump then refetches it. */
    private var onOccurrencePut: ((id: Int, date: String, skipped: Boolean) -> Unit)? = null

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
        rawListBody = null
        occurrenceStore.clear()
        occurrencesStatus = 200
        occurrencePutStatus = 200
        onOccurrencePut = null
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
                rawListBody != null -> jsonResponse(200, rawListBody!!)
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
                        // The backend's creation-time convenience (ADR-0024):
                        // an empty start date is set to the creation day.
                        startDate = create.start_date ?: CREATION_DAY,
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
                            // An update always carries the start date: it can
                            // be changed, never unset (ADR-0024).
                            start_date = update.start_date,
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

            method == "GET" &&
                path.matches(Regex("/api/recurring-costs/\\d+/occurrences")) -> {
                val id = path.removePrefix("/api/recurring-costs/").substringBefore("/").toInt()
                when {
                    occurrencesStatus != 200 -> jsonResponse(
                        occurrencesStatus,
                        """{"detail":"boom"}""",
                    )
                    else -> jsonResponse(
                        200,
                        json.encodeToString(occurrenceStore[id].orEmpty()),
                    )
                }
            }

            method == "PUT" &&
                path.matches(Regex("/api/recurring-costs/\\d+/occurrences/[^/]+")) -> {
                val remainder = path.removePrefix("/api/recurring-costs/")
                val id = remainder.substringBefore("/").toInt()
                val date = remainder.removePrefix("$id/occurrences/")
                when {
                    occurrencePutStatus != 200 -> jsonResponse(
                        occurrencePutStatus,
                        """{"detail":"Could not skip a paid occurrence"}""",
                    )
                    else -> {
                        val update = json.decodeFromString<RecurringOccurrenceUpdateRequest>(body)
                        val rows = occurrenceStore.getOrPut(id) { mutableListOf() }
                        val index = rows.indexOfFirst { it.date == date }
                        if (index >= 0) {
                            rows[index] = rows[index].copy(skipped = update.skipped)
                        }
                        onOccurrencePut?.invoke(id, date, update.skipped)
                        jsonResponse(200, json.encodeToString(rows))
                    }
                }
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

    /** Every seeded definition carries the fixture creation day as its
     * start date (ADR-0024): a definition's start date is always present. */
    private fun costDto(
        id: Int,
        name: String,
        amount: String = "10.00",
        intervalValue: Int = 1,
        intervalUnit: IntervalUnit = IntervalUnit.MONTHS,
        startDate: String = CREATION_DAY,
        nextDue: String = "2026-09-05",
        nextUnpaid: String = "2026-09-05",
        backlog: Int = 0,
    ) = RecurringCostDto(
        id = id,
        name = name,
        amount = amount,
        interval_value = intervalValue,
        interval_unit = intervalUnit,
        start_date = startDate,
        next_due_date = nextDue,
        next_unpaid_occurrence_date = nextUnpaid,
        backlog_count = backlog,
        created_at = "2026-08-01T10:00:00Z",
    )

    /** One Occurrences section row (web ADR-0026): the Occurrence's own
     * date and whether the user excused it. */
    private fun occurrence(date: String, skipped: Boolean = false) =
        RecurringOccurrenceDto(date = date, skipped = skipped)

    /** Seed a definition's Occurrences read; the section renders the rows
     * verbatim in this order (the server's order is authoritative). */
    private fun seedOccurrences(id: Int, vararg rows: RecurringOccurrenceDto) {
        occurrenceStore[id] = rows.toMutableList()
    }

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (RecurringCostsViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

    private fun occurrenceCalls(): List<RecordedCall> =
        calls.toList().filter { it.path.contains("/occurrences") }

    // --- Load: the next-due order with the derived state intact ---

    @Test
    fun `the list loads ordered by next due date with the backlog state`() = runBlocking {
        seed(
            // Deliberately out of next-due order: the screen renders sorted.
            costDto(1, "Rent", amount = "800.00", nextDue = "2026-09-01", nextUnpaid = "2026-09-01"),
            costDto(2, "Netflix", amount = "9.99", nextDue = "2026-08-15", nextUnpaid = "2026-08-01", backlog = 1),
        )
        createViewModel()
        awaitLoaded()

        val costs = viewModel.uiState.value.costs
        assertEquals(listOf(2, 1), costs.map { it.id })
        val netflix = costs.first { it.id == 2 }
        assertEquals(1, netflix.backlog_count)
        // The row's data is the API's derived state, never computed locally.
        assertEquals("2026-08-15", netflix.next_due_date)
        assertEquals("2026-08-01", netflix.next_unpaid_occurrence_date)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `a definition JSON still carrying the backend's dropped derived fields parses like one without them`() = runBlocking {
        // The DTO dropped the derived `overdue` field (web ADR-0025 /
        // ticket #45) and the card's `next_skip_action` (web ADR-0026 /
        // ticket #46); a backend that still sends them (either deploy
        // order) must parse cleanly — the JSON config ignores unknown
        // keys — and the badge state reads from backlog_count alone.
        rawListBody = """
            [
              {
                "id": 1, "name": "Rent", "amount": "800.00",
                "interval_value": 1, "interval_unit": "months",
                "start_date": "2026-08-01", "next_due_date": "2026-09-01",
                "next_unpaid_occurrence_date": "2026-08-01",
                "backlog_count": 2, "overdue": true,
                "next_skip_action": "skip",
                "created_at": "2026-08-01T10:00:00Z"
              },
              {
                "id": 2, "name": "Netflix", "amount": "9.99",
                "interval_value": 1, "interval_unit": "months",
                "start_date": "2026-08-01", "next_due_date": "2026-09-05",
                "next_unpaid_occurrence_date": "2026-09-05",
                "backlog_count": 0,
                "next_skip_action": "skip",
                "created_at": "2026-08-01T10:00:00Z"
              }
            ]
        """.trimIndent()
        createViewModel()
        awaitLoaded()

        val costs = viewModel.uiState.value.costs
        assertEquals(listOf(1, 2), costs.map { it.id })
        assertEquals(2, costs.first { it.id == 1 }.backlog_count)
        assertEquals(0, costs.first { it.id == 2 }.backlog_count)
    }

    @Test
    fun `a write elsewhere refetches the list in the background`() = runBlocking {
        seed(costDto(1, "Rent", nextDue = "2026-09-01", backlog = 1))
        createViewModel()
        awaitLoaded()
        assertEquals(1, viewModel.uiState.value.costs.size)

        // A link paid on the Transactions tab bumps the version; the list's
        // derived state re-renders from the fresh API response (ADR-0002).
        store[0] = store[0].copy(
            backlog_count = 0,
            next_unpaid_occurrence_date = "2026-10-01",
        )
        DataVersion.bump()
        awaitState { it.costs.first().next_unpaid_occurrence_date == "2026-10-01" }
        val refreshed = viewModel.uiState.value.costs.first()
        assertEquals(0, refreshed.backlog_count)
    }

    // --- Create ---

    @Test
    fun `create defaults the draft to a monthly definition and sends it whole`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        val modal = viewModel.uiState.value.modal!!
        // The web form's defaults: monthly, every 1, no start date (empty =
        // today at creation, ADR-0024), and no due-date override — the
        // override is gone from the model entirely.
        assertEquals(IntervalUnit.MONTHS, modal.intervalUnit)
        assertEquals("1", modal.intervalValue)
        assertEquals("", modal.startDate)
        assertFalse(modal.editing)

        viewModel.onNameChange("Rent")
        viewModel.onAmountChange("800.00")
        viewModel.onStartDateChange("2026-08-01")
        viewModel.submit()
        awaitState { it.modal == null && it.costs.any { c -> c.name == "Rent" } }

        val create = json.decodeFromString<RecurringCostCreateRequest>(call("POST", "/api/recurring-costs").body)
        assertEquals("Rent", create.name)
        assertEquals("800.00", create.amount)
        assertEquals(1, create.interval_value)
        assertEquals(IntervalUnit.MONTHS, create.interval_unit)
        assertEquals("2026-08-01", create.start_date)
        // The due-date override never reaches the wire (ADR-0024).
        assertFalse(call("POST", "/api/recurring-costs").body.contains("due_"))
        assertEquals(listOf("Rent"), viewModel.uiState.value.costs.map { it.name })
    }

    @Test
    fun `an unset start date stays off the create wire and the response carries the creation day`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Rent")
        viewModel.onAmountChange("800.00")
        viewModel.submit()
        awaitState { it.modal == null }

        // "Start today" needs no typing: the request omits the empty start
        // date, and the fake backend materializes the creation day in the
        // stored definition it returns (ADR-0024).
        val body = call("POST", "/api/recurring-costs").body
        assertFalse(body.contains("start_date"))
        assertFalse(body.contains("due_"))
        val create = json.decodeFromString<RecurringCostCreateRequest>(body)
        assertNull(create.start_date)
        assertEquals(CREATION_DAY, viewModel.uiState.value.costs.single().start_date)
    }

    @Test
    fun `every interval unit carries only the start date — the override fields are gone from the wire`() = runBlocking {
        createViewModel()
        awaitLoaded()

        // A yearly definition with a start date — the old override pair's
        // (month+day) shape now lives in the start date alone (ADR-0024).
        viewModel.openCreate()
        viewModel.onNameChange("Car tax")
        viewModel.onAmountChange("200.00")
        viewModel.onIntervalUnitChange(IntervalUnit.YEARS)
        viewModel.onStartDateChange("2026-06-30")
        viewModel.submit()
        awaitState { it.modal == null }

        val create = json.decodeFromString<RecurringCostCreateRequest>(call("POST", "/api/recurring-costs").body)
        assertEquals(IntervalUnit.YEARS, create.interval_unit)
        assertEquals("2026-06-30", create.start_date)
        assertEquals("2026-06-30", viewModel.uiState.value.costs.single().start_date)
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
    fun `edit prefills the definition with its start date and the patch always carries the date`() = runBlocking {
        seed(
            costDto(
                1, "Rent", amount = "800.00", startDate = "2026-01-01",
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
        // The definition's one date, always present (ADR-0024).
        assertEquals("2026-01-01", modal.startDate)

        // The date can be changed — a whole-form PATCH carries the new one,
        // never a null (the old explicit-null clear is gone with the
        // override; the backend rejects it).
        viewModel.onStartDateChange("2026-02-01")
        viewModel.onNameChange("Rent (home)")
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/recurring-costs/1")
        assertFalse(patch.body.contains("null"))
        assertFalse(patch.body.contains("due_"))
        val update = json.decodeFromString<RecurringCostUpdateRequest>(patch.body)
        assertEquals("Rent (home)", update.name)
        assertEquals("800.00", update.amount)
        assertEquals("2026-02-01", update.start_date)
        // The row landed, re-sorted in place.
        assertEquals("Rent (home)", viewModel.uiState.value.costs.first().name)
        assertEquals("2026-02-01", viewModel.uiState.value.costs.first().start_date)
    }

    @Test
    fun `an edit cannot clear the start date - the save gate blocks an empty date`() = runBlocking {
        seed(
            costDto(
                1, "Rent", amount = "800.00", startDate = "2026-01-01",
                nextDue = "2026-09-15", nextUnpaid = "2026-09-15",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        // The web form's edit gate (ADR-0024): the start date can be
        // changed, never unset — an empty date blocks the save, like the
        // web's required date input.
        viewModel.onStartDateChange("")
        assertFalse(viewModel.uiState.value.modal!!.canSubmit)
        viewModel.submit()

        assertTrue(calls.toList().none { it.method == "PATCH" })
        assertTrue(viewModel.uiState.value.modal != null)

        // Re-picking a date unblocks the save.
        viewModel.onStartDateChange("2026-01-01")
        assertTrue(viewModel.uiState.value.modal!!.canSubmit)
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

    // --- The Occurrences section (web ADR-0026) ---

    @Test
    fun `an edit open fetches the occurrences read and renders its rows verbatim`() = runBlocking {
        seed(costDto(1, "Rent", backlog = 2))
        // The server's order is authoritative: the next incoming Unpaid row
        // on top, then newest-first down to the oldest. The section must
        // render it verbatim — a client never re-sorts.
        seedOccurrences(
            1,
            occurrence("2026-09-05"),
            occurrence("2026-09-01"),
            occurrence("2026-08-25", skipped = true),
            occurrence("2026-08-01"),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrences != null }

        val modal = viewModel.uiState.value.modal!!
        assertEquals(
            listOf("2026-09-05", "2026-09-01", "2026-08-25", "2026-08-01"),
            modal.occurrences!!.map { it.date },
        )
        assertEquals(listOf(false, false, true, false), modal.occurrences!!.map { it.skipped })
        assertNull(modal.occurrencesError)
        assertNull(modal.togglingDate)
        // The read happened exactly once, on the definition's own id.
        assertEquals(1, occurrenceCalls().count { it.method == "GET" })
        assertEquals("/api/recurring-costs/1/occurrences", occurrenceCalls().single { it.method == "GET" }.path)
    }

    @Test
    fun `a create never fetches the occurrences read`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        val modal = viewModel.uiState.value.modal!!
        // A definition under creation has no id yet — its first Occurrence
        // is only decided at creation, so the section stays absent.
        assertNull(modal.occurrences)
        assertNull(modal.occurrencesError)
        assertTrue(occurrenceCalls().none { it.method == "GET" })
    }

    @Test
    fun `a failed occurrences read shows the section error and keeps the modal usable`() = runBlocking {
        seed(costDto(1, "Rent"))
        createViewModel()
        awaitLoaded()

        occurrencesStatus = 500
        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrencesError != null }

        val modal = viewModel.uiState.value.modal!!
        assertEquals("Could not load the occurrences.", modal.occurrencesError)
        assertNull(modal.occurrences)
        // The modal itself stays usable: the fields are still editable.
        assertTrue(modal.canSubmit)
        viewModel.onNameChange("Rent (home)")
        assertTrue(viewModel.uiState.value.modal!!.canSubmit)
    }

    @Test
    fun `a skip press PUTs skipped true and swaps in the refreshed read`() = runBlocking {
        seed(costDto(1, "Rent", backlog = 1))
        seedOccurrences(1, occurrence("2026-09-05"), occurrence("2026-09-01"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrences != null }

        // Skip the live top row: the fake flips its state in the store and
        // answers the refreshed read, which replaces the section's rows.
        viewModel.toggleOccurrence(viewModel.uiState.value.modal!!.occurrences!!.first())
        awaitState {
            it.modal?.occurrences?.first { row -> row.date == "2026-09-05" }?.skipped == true
        }

        val put = occurrenceCalls().single { it.method == "PUT" }
        assertEquals("/api/recurring-costs/1/occurrences/2026-09-05", put.path)
        val update = json.decodeFromString<RecurringOccurrenceUpdateRequest>(put.body)
        assertTrue(update.skipped)
        val modal = viewModel.uiState.value.modal!!
        assertNull(modal.occurrencesError)
        assertNull(modal.togglingDate)
        // The refreshed read keeps the server's order: the row the press
        // excused greys in place, the following row stays live under it.
        assertEquals(listOf(true, false), modal.occurrences!!.map { it.skipped })
    }

    @Test
    fun `an un-skip press PUTs skipped false and restores the row`() = runBlocking {
        seed(costDto(1, "Rent"))
        seedOccurrences(1, occurrence("2026-09-05"), occurrence("2026-08-25", skipped = true))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrences != null }

        val excused = viewModel.uiState.value.modal!!.occurrences!!.first { it.skipped }
        viewModel.toggleOccurrence(excused)
        awaitState {
            it.modal?.occurrences?.first { row -> row.date == "2026-08-25" }?.skipped == false
        }

        val put = occurrenceCalls().single { it.method == "PUT" }
        assertEquals("/api/recurring-costs/1/occurrences/2026-08-25", put.path)
        val update = json.decodeFromString<RecurringOccurrenceUpdateRequest>(put.body)
        assertFalse(update.skipped)
        assertNull(viewModel.uiState.value.modal!!.occurrencesError)
    }

    @Test
    fun `a double tap on the same row fires one write`() = runBlocking {
        seed(costDto(1, "Rent", backlog = 1))
        seedOccurrences(1, occurrence("2026-09-05"), occurrence("2026-09-01"))
        // The first press's response is held until both taps have been
        // delivered, so the second tap deterministically lands while the
        // first write is still in flight.
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "PUT" && request.path?.contains("/occurrences/") == true) {
                    release.await(5, TimeUnit.SECONDS)
                }
                return route(request)
            }
        }
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrences != null }

        val live = viewModel.uiState.value.modal!!.occurrences!!.first { !it.skipped }
        viewModel.toggleOccurrence(live)
        viewModel.toggleOccurrence(live)
        release.countDown()
        awaitState {
            it.modal?.occurrences?.first { row -> row.date == "2026-09-05" }?.skipped == true
        }

        // One press, one write: the second tap could not flip the state
        // twice (skip then un-skip).
        assertEquals(1, occurrenceCalls().count { it.method == "PUT" })
        assertNull(viewModel.uiState.value.modal!!.togglingDate)
    }

    @Test
    fun `a failed toggle keeps the rows and shows the web message`() = runBlocking {
        seed(costDto(1, "Rent", backlog = 1))
        seedOccurrences(1, occurrence("2026-09-05"), occurrence("2026-09-01"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrences != null }

        occurrencePutStatus = 500
        viewModel.toggleOccurrence(viewModel.uiState.value.modal!!.occurrences!!.first())
        awaitState { it.modal?.occurrencesError != null }

        val modal = viewModel.uiState.value.modal!!
        assertEquals("Could not update the occurrence.", modal.occurrencesError)
        assertNull(modal.togglingDate)
        // The held rows stay on screen — only the action failed.
        assertEquals(2, modal.occurrences!!.size)

        // The next press clears the error and works.
        occurrencePutStatus = 200
        viewModel.toggleOccurrence(modal.occurrences!!.first())
        awaitState {
            it.modal?.occurrences?.first { row -> row.date == "2026-09-05" }?.skipped == true
        }
        assertNull(viewModel.uiState.value.modal!!.occurrencesError)
        // Both presses hit the wire: the failed one and the successful one.
        assertEquals(2, occurrenceCalls().count { it.method == "PUT" })
    }

    @Test
    fun `a skip write's data-version bump refetches the list and re-derives the badge`() = runBlocking {
        // The write refreshes the definition's derived state on the cards
        // behind the modal (ADR-0002): the badge and the next-due walk
        // over the excused Occurrence, exactly as the backend re-derives
        // them from the stored skips.
        seed(costDto(1, "Rent", nextDue = "2026-09-01", nextUnpaid = "2026-08-25", backlog = 1))
        seedOccurrences(1, occurrence("2026-09-05"), occurrence("2026-09-01"))
        onOccurrencePut = { id, _, _ ->
            val index = store.indexOfFirst { it.id == id }
            if (index >= 0) {
                store[index] = store[index].copy(
                    backlog_count = 0,
                    next_unpaid_occurrence_date = "2026-09-01",
                )
            }
        }
        createViewModel()
        awaitLoaded()
        assertEquals(1, calls.toList().count { it.method == "GET" && it.path == "/api/recurring-costs" })

        viewModel.openEdit(viewModel.uiState.value.costs.first { it.id == 1 })
        awaitState { it.modal?.occurrences != null }
        viewModel.toggleOccurrence(viewModel.uiState.value.modal!!.occurrences!!.first())
        awaitState {
            it.costs.first { c -> c.id == 1 }.backlog_count == 0 &&
                it.costs.first { c -> c.id == 1 }.next_unpaid_occurrence_date == "2026-09-01"
        }

        // The list refetched exactly once more — the write's own bump.
        assertEquals(
            2,
            calls.toList().count { it.method == "GET" && it.path == "/api/recurring-costs" },
        )
        val cost = viewModel.uiState.value.costs.first { it.id == 1 }
        assertEquals(0, cost.backlog_count)
        assertEquals("2026-09-01", cost.next_unpaid_occurrence_date)
        // The modal is untouched by the refetch.
        assertEquals("Rent", viewModel.uiState.value.modal!!.name)
    }
}
