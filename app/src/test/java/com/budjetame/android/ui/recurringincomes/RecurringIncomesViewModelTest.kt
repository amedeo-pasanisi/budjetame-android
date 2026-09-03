package com.budjetame.android.ui.recurringincomes

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringIncomeApi
import com.budjetame.android.data.api.RecurringIncomeCreateRequest
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RecurringIncomeUpdateRequest
import com.budjetame.android.data.api.SkipAction
import com.budjetame.android.data.recurringincome.ApiRecurringIncomeRepository
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

/**
 * The Recurring Incomes flow tested at the single seam (the HTTP API), the
 * mirror of the Recurring Costs suite (ADR-0011): the ViewModel is driven
 * through the real repository, Retrofit, OkHttp, and a MockWebServer whose
 * dispatcher is a small stateful fake of the /recurring-incomes resource —
 * the list with the derived dates, the create/PATCH/delete writes with the
 * backend's duplicate-name rule (names unique per Account,
 * case-insensitively) and the web's exact 409 message, and the Skip/Un-skip
 * toggle (ADR-0016): each press pops the next recorded fixture state for
 * that definition, emulating the backend's re-derivation. Request bodies
 * are captured for assertions.
 */
class RecurringIncomesViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val body: String)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: RecurringIncomesViewModel

    private val store = mutableListOf<RecurringIncomeDto>()
    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    private var nextId = 1
    private var listStatus = 200
    private var createStatus = 201
    private var updateStatus = 200
    private var deleteStatus = 204

    /** The Skip/Un-skip button's fixture responses (ADR-0016), one per
     * definition: each press pops the next recorded state — the fake's
     * emulation of the backend's derived re-derivation — and stores it, so
     * the toggle's own data-version bump refetches a consistent list. */
    private val skipScripts = mutableMapOf<Int, ArrayDeque<RecurringIncomeDto>>()
    private var toggleStatus = 200

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
        skipScripts.clear()
        toggleStatus = 200
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
        val repository = ApiRecurringIncomeRepository(client.create(RecurringIncomeApi::class.java))
        viewModel = RecurringIncomesViewModel(repository)
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val body = request.body.readUtf8()
        calls.add(RecordedCall(method, path, body))

        return when {
            method == "GET" && path == "/api/recurring-incomes" -> when {
                listStatus != 200 -> jsonResponse(listStatus, """{"detail":"boom"}""")
                else -> jsonResponse(200, json.encodeToString(store))
            }

            method == "POST" && path == "/api/recurring-incomes" -> when {
                createStatus != 201 -> jsonResponse(
                    createStatus,
                    """{"detail":"A Recurring Income with this name already exists"}""",
                )
                else -> {
                    val create = json.decodeFromString<RecurringIncomeCreateRequest>(body)
                    if (store.any { it.name.equals(create.name, ignoreCase = true) }) {
                        return jsonResponse(
                            409,
                            """{"detail":"A Recurring Income with this name already exists"}""",
                        )
                    }
                    val income = incomeDto(
                        id = nextId++,
                        name = create.name,
                        amount = create.amount,
                        intervalValue = create.interval_value,
                        intervalUnit = create.interval_unit,
                        startDate = create.start_date,
                        dueDay = create.due_day,
                        dueMonth = create.due_month,
                    )
                    store.add(income)
                    jsonResponse(201, json.encodeToString(income))
                }
            }

            method == "PATCH" && path.matches(Regex("/api/recurring-incomes/\\d+")) -> {
                val id = path.removePrefix("/api/recurring-incomes/").toInt()
                when {
                    updateStatus != 200 -> jsonResponse(
                        updateStatus,
                        """{"detail":"A Recurring Income with this name already exists"}""",
                    )
                    else -> {
                        val update = json.decodeFromString<RecurringIncomeUpdateRequest>(body)
                        val index = store.indexOfFirst { it.id == id }
                        if (index < 0) return jsonResponse(403, """{"detail":"Recurring Income not found"}""")
                        val existing = store[index]
                        if (store.any {
                                it.id != id && it.name.equals(update.name, ignoreCase = true)
                            }
                        ) {
                            return jsonResponse(
                                409,
                                """{"detail":"A Recurring Income with this name already exists"}""",
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

            method == "DELETE" && path.matches(Regex("/api/recurring-incomes/\\d+")) -> {
                val id = path.removePrefix("/api/recurring-incomes/").toInt()
                store.removeAll { it.id == id }
                MockResponse().setResponseCode(deleteStatus)
            }

            method == "POST" && path.matches(Regex("/api/recurring-incomes/\\d+/skip-toggle")) -> {
                val id = path.removePrefix("/api/recurring-incomes/").substringBefore("/").toInt()
                val index = store.indexOfFirst { it.id == id }
                if (index < 0) return jsonResponse(403, """{"detail":"Recurring Income not found"}""")
                when {
                    toggleStatus != 200 -> jsonResponse(
                        toggleStatus,
                        """{"detail":"boom"}""",
                    )
                    else -> {
                        val script = skipScripts[id]
                        val toggled = if (script != null && script.isNotEmpty()) {
                            script.removeFirst()
                        } else {
                            store[index]
                        }
                        store[index] = toggled
                        jsonResponse(200, json.encodeToString(toggled))
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

    private fun seed(vararg incomes: RecurringIncomeDto) {
        store.addAll(incomes)
        incomes.forEach { nextId = maxOf(nextId, it.id + 1) }
    }

    private fun incomeDto(
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
        nextSkipAction: SkipAction = SkipAction.SKIP,
    ) = RecurringIncomeDto(
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
        next_skip_action = nextSkipAction,
        created_at = "2026-08-01T10:00:00Z",
    )

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    private suspend fun awaitState(predicate: (RecurringIncomesViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun call(method: String, path: String): RecordedCall =
        calls.toList().first { it.method == method && it.path == path }

    // --- Load: the next-due order with the derived state intact ---

    @Test
    fun `the list loads ordered by next due date with the backlog and overdue state`() = runBlocking {
        seed(
            // Deliberately out of next-due order: the screen renders sorted.
            incomeDto(1, "Salary", amount = "2500.00", nextDue = "2026-09-01", nextUnpaid = "2026-09-01"),
            incomeDto(2, "Freelance", amount = "300.00", nextDue = "2026-08-15", nextUnpaid = "2026-08-01", backlog = 1, overdue = true),
        )
        createViewModel()
        awaitLoaded()

        val incomes = viewModel.uiState.value.incomes
        assertEquals(listOf(2, 1), incomes.map { it.id })
        val freelance = incomes.first { it.id == 2 }
        assertEquals(1, freelance.backlog_count)
        assertTrue(freelance.overdue)
        // The row's data is the API's derived state, never computed locally.
        assertEquals("2026-08-15", freelance.next_due_date)
        assertEquals("2026-08-01", freelance.next_unpaid_occurrence_date)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun `a write elsewhere refetches the list in the background`() = runBlocking {
        seed(incomeDto(1, "Salary", nextDue = "2026-09-01", backlog = 1, overdue = true))
        createViewModel()
        awaitLoaded()
        assertEquals(1, viewModel.uiState.value.incomes.size)

        // A link paid on the Transactions tab bumps the version; the list's
        // derived state re-renders from the fresh API response (ADR-0002).
        store[0] = store[0].copy(
            backlog_count = 0,
            overdue = false,
            next_unpaid_occurrence_date = "2026-10-01",
        )
        DataVersion.bump()
        awaitState { it.incomes.first().next_unpaid_occurrence_date == "2026-10-01" }
        val refreshed = viewModel.uiState.value.incomes.first()
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

        viewModel.onNameChange("Salary")
        viewModel.onAmountChange("2500.00")
        viewModel.onStartDateChange("2026-08-01")
        viewModel.onDueDayChange(28)
        viewModel.submit()
        awaitState { it.modal == null && it.incomes.any { i -> i.name == "Salary" } }

        val create = json.decodeFromString<RecurringIncomeCreateRequest>(call("POST", "/api/recurring-incomes").body)
        assertEquals("Salary", create.name)
        assertEquals("2500.00", create.amount)
        assertEquals(1, create.interval_value)
        assertEquals(IntervalUnit.MONTHS, create.interval_unit)
        assertEquals("2026-08-01", create.start_date)
        assertEquals(28, create.due_day)
        assertNull(create.due_month)
        assertEquals(listOf("Salary"), viewModel.uiState.value.incomes.map { it.name })
    }

    @Test
    fun `an unset start date and override stay off the create wire`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Salary")
        viewModel.onAmountChange("2500.00")
        viewModel.submit()
        awaitState { it.modal == null }

        val body = call("POST", "/api/recurring-incomes").body
        assertFalse(body.contains("start_date"))
        assertFalse(body.contains("due_day"))
        assertFalse(body.contains("due_month"))
        val create = json.decodeFromString<RecurringIncomeCreateRequest>(body)
        assertNull(create.start_date)
        assertNull(create.due_day)
        assertNull(create.due_month)
    }

    @Test
    fun `a year interval sends the month and day pair`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Bonus")
        viewModel.onAmountChange("5000.00")
        viewModel.onIntervalUnitChange(IntervalUnit.YEARS)
        viewModel.onDueMonthChange(12)
        viewModel.onDueDayChange(15)
        viewModel.submit()
        awaitState { it.modal == null }

        val create = json.decodeFromString<RecurringIncomeCreateRequest>(call("POST", "/api/recurring-incomes").body)
        assertEquals(IntervalUnit.YEARS, create.interval_unit)
        assertEquals(12, create.due_month)
        assertEquals(15, create.due_day)
    }

    @Test
    fun `a half-picked year pair never submits`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("Bonus")
        viewModel.onAmountChange("5000.00")
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
        viewModel.onNameChange("Salary")
        viewModel.onAmountChange("2500.00")
        viewModel.onIntervalUnitChange(IntervalUnit.MONTHS)
        viewModel.onDueDayChange(15)
        viewModel.onIntervalUnitChange(IntervalUnit.DAYS)
        viewModel.submit()
        awaitState { it.modal == null }

        val create = json.decodeFromString<RecurringIncomeCreateRequest>(call("POST", "/api/recurring-incomes").body)
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
        viewModel.onNameChange("Salary")
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
        seed(incomeDto(1, "Salary"))
        createViewModel()
        awaitLoaded()

        viewModel.openCreate()
        viewModel.onNameChange("salary")
        viewModel.onAmountChange("2500.00")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals(
            "A recurring income with this name already exists.",
            viewModel.uiState.value.modal?.error,
        )
        assertEquals(1, viewModel.uiState.value.incomes.size)
    }

    @Test
    fun `a 422 shows the check-your-fields message`() = runBlocking {
        createViewModel()
        awaitLoaded()

        createStatus = 422
        viewModel.openCreate()
        viewModel.onNameChange("Salary")
        viewModel.onAmountChange("2500.00")
        viewModel.submit()
        awaitState { it.modal?.error == "Check the fields and try again." }
    }

    // --- Edit ---

    @Test
    fun `edit prefills the definition and the patch sends the whole form with explicit nulls`() = runBlocking {
        seed(
            incomeDto(
                1, "Salary", amount = "2500.00", startDate = "2026-01-01", dueDay = 28,
                nextDue = "2026-09-28", nextUnpaid = "2026-09-28",
            ),
        )
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.incomes.first { it.id == 1 })
        val modal = viewModel.uiState.value.modal!!
        assertTrue(modal.editing)
        assertEquals("Salary", modal.name)
        assertEquals("2500.00", modal.amount)
        assertEquals("1", modal.intervalValue)
        assertEquals(IntervalUnit.MONTHS, modal.intervalUnit)
        assertEquals("2026-01-01", modal.startDate)
        assertEquals(28, modal.dueDay)
        assertNull(modal.dueMonth)

        // Clearing the start date travels as an explicit null: the PATCH
        // field present is applied even when null (the unset = creation
        // date semantics are the backend's).
        viewModel.onStartDateChange("")
        viewModel.onNameChange("Salary (main)")
        viewModel.submit()
        awaitState { it.modal == null }

        val patch = call("PATCH", "/api/recurring-incomes/1")
        assertTrue(patch.body.contains("\"start_date\":null"))
        val update = json.decodeFromString<RecurringIncomeUpdateRequest>(patch.body)
        assertEquals("Salary (main)", update.name)
        assertEquals("2500.00", update.amount)
        assertEquals(28, update.due_day)
        assertNull(update.start_date)
        // The row landed, re-sorted in place.
        assertEquals("Salary (main)", viewModel.uiState.value.incomes.first().name)
    }

    @Test
    fun `an edit renaming onto another income shows the web's exact message`() = runBlocking {
        seed(incomeDto(1, "Salary"), incomeDto(2, "Freelance"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.incomes.first { it.id == 2 })
        viewModel.onNameChange("SALARY")
        viewModel.submit()
        awaitState { it.modal?.error != null }

        assertEquals(
            "A recurring income with this name already exists.",
            viewModel.uiState.value.modal?.error,
        )
        assertEquals("Freelance", viewModel.uiState.value.incomes.first { it.id == 2 }.name)
    }

    // --- Delete ---

    @Test
    fun `delete is tap-again confirmed and removes the row`() = runBlocking {
        seed(incomeDto(1, "Salary"), incomeDto(2, "Freelance"))
        createViewModel()
        awaitLoaded()

        viewModel.openEdit(viewModel.uiState.value.incomes.first { it.id == 1 })
        viewModel.onDeleteTap()
        assertTrue(viewModel.uiState.value.modal!!.confirmingDelete)
        viewModel.onDeleteTap()
        awaitState { it.modal == null && it.incomes.none { i -> i.id == 1 } }

        assertTrue(calls.toList().any { it.method == "DELETE" && it.path == "/api/recurring-incomes/1" })
        assertEquals(listOf(2), viewModel.uiState.value.incomes.map { it.id })
    }

    @Test
    fun `a failed delete keeps the modal with the error`() = runBlocking {
        seed(incomeDto(1, "Salary"))
        createViewModel()
        awaitLoaded()

        deleteStatus = 500
        viewModel.openEdit(viewModel.uiState.value.incomes.first { it.id == 1 })
        viewModel.onDeleteTap()
        viewModel.onDeleteTap()
        awaitState { it.modal?.error != null }

        assertEquals("Could not delete the recurring income.", viewModel.uiState.value.modal?.error)
        assertEquals(1, viewModel.uiState.value.incomes.size)
        assertEquals(listOf(1), viewModel.uiState.value.incomes.map { it.id })
    }

    // --- Load error + retry ---

    @Test
    fun `a load failure shows the error and retry refetches`() = runBlocking {
        listStatus = 500
        createViewModel()
        awaitLoaded()
        assertEquals("Could not load your recurring incomes.", viewModel.uiState.value.loadError)

        listStatus = 200
        seed(incomeDto(1, "Salary"))
        viewModel.retry()
        awaitState { !it.loading && it.incomes.isNotEmpty() }

        assertNull(viewModel.uiState.value.loadError)
        assertEquals("Salary", viewModel.uiState.value.incomes.first().name)
    }

    // --- Skip / Un-skip (ADR-0016), mirroring the Costs side ---

    /** The recorded Skip/Un-skip presses. */
    private fun skipToggleCalls(): List<RecordedCall> =
        calls.toList().filter { it.method == "POST" && it.path.endsWith("/skip-toggle") }

    @Test
    fun `a skip press calls the incomes skip-toggle and swaps in the refreshed row`() = runBlocking {
        // Salary has one Unpaid, un-Skipped Occurrence due (badge 1,
        // Overdue); Rent is clean. One press excuses it: the response's
        // refreshed state re-renders the row — badge gone, Overdue cleared,
        // the button reads Un-skip — and the summary re-totals.
        seed(
            incomeDto(1, "Salary", nextDue = "2026-09-01", nextUnpaid = "2026-08-25", backlog = 1, overdue = true),
            incomeDto(2, "Rent", nextDue = "2026-09-05", nextUnpaid = "2026-09-05"),
        )
        skipScripts[1] = ArrayDeque(
            listOf(
                incomeDto(
                    1, "Salary", nextDue = "2026-09-01", nextUnpaid = "2026-09-01",
                    backlog = 0, overdue = false, nextSkipAction = SkipAction.UNSKIP,
                ),
            ),
        )
        createViewModel()
        awaitLoaded()
        assertEquals(1, viewModel.uiState.value.overdueCount)
        assertEquals(1, viewModel.uiState.value.unpaidCount)

        viewModel.toggleSkip(viewModel.uiState.value.incomes.first { it.id == 1 })
        awaitState {
            it.togglingId == null &&
                it.incomes.first { i -> i.id == 1 }.next_skip_action == SkipAction.UNSKIP
        }

        assertEquals(1, skipToggleCalls().size)
        assertEquals("/api/recurring-incomes/1/skip-toggle", skipToggleCalls().first().path)
        val salary = viewModel.uiState.value.incomes.first { it.id == 1 }
        assertEquals(0, salary.backlog_count)
        assertFalse(salary.overdue)
        assertEquals(SkipAction.UNSKIP, salary.next_skip_action)
        assertEquals(0, viewModel.uiState.value.overdueCount)
        assertEquals(0, viewModel.uiState.value.unpaidCount)
        // The list order is unchanged when the dates do not move.
        assertEquals(listOf(1, 2), viewModel.uiState.value.incomes.map { it.id })
    }

    @Test
    fun `repeated presses clear the backlog oldest-first then an un-skip restores one`() = runBlocking {
        // A daily income missed for ten days (badge 10): each press skips
        // the oldest Unpaid Occurrence, so the badge ticks 10, 9, ..., 0 and
        // Overdue clears with the last one; once nothing is left to skip the
        // button reads Un-skip, and the next press restores the oldest
        // Skipped Occurrence (badge 1, Overdue back, button reads Skip).
        val salary = incomeDto(1, "Salary", nextDue = "2026-09-05", nextUnpaid = "2026-08-27", backlog = 10, overdue = true)
        seed(salary)
        skipScripts[1] = ArrayDeque(
            (1..10).map { press ->
                salary.copy(
                    backlog_count = 10 - press,
                    overdue = press < 10,
                    next_skip_action = if (press == 10) SkipAction.UNSKIP else SkipAction.SKIP,
                )
            } + salary.copy(backlog_count = 1, overdue = true, next_skip_action = SkipAction.SKIP),
        )
        createViewModel()
        awaitLoaded()

        for (press in 1..10) {
            viewModel.toggleSkip(viewModel.uiState.value.incomes.first { it.id == 1 })
            awaitState {
                it.togglingId == null &&
                    it.incomes.first { i -> i.id == 1 }.backlog_count == 10 - press
            }
            val row = viewModel.uiState.value.incomes.first { it.id == 1 }
            assertEquals(
                if (press == 10) SkipAction.UNSKIP else SkipAction.SKIP,
                row.next_skip_action,
            )
        }
        assertEquals(0, viewModel.uiState.value.unpaidCount)
        assertFalse(viewModel.uiState.value.incomes.first { it.id == 1 }.overdue)

        // The Un-skip press restores the oldest Skipped Occurrence.
        viewModel.toggleSkip(viewModel.uiState.value.incomes.first { it.id == 1 })
        awaitState {
            it.togglingId == null &&
                it.incomes.first { i -> i.id == 1 }.backlog_count == 1
        }
        assertEquals(SkipAction.SKIP, viewModel.uiState.value.incomes.first { it.id == 1 }.next_skip_action)
        assertEquals(1, viewModel.uiState.value.unpaidCount)
        assertTrue(viewModel.uiState.value.incomes.first { it.id == 1 }.overdue)
        assertEquals(11, skipToggleCalls().size)
    }

    @Test
    fun `a double tap on the same row fires one toggle`() = runBlocking {
        seed(incomeDto(1, "Salary", backlog = 1, overdue = true))
        skipScripts[1] = ArrayDeque(
            listOf(
                incomeDto(1, "Salary", backlog = 0, nextSkipAction = SkipAction.UNSKIP),
            ),
        )
        // The first press's response is held until both taps have been
        // delivered, so the second tap deterministically lands while the
        // first toggle is still in flight.
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST" && request.path?.endsWith("/skip-toggle") == true) {
                    release.await(5, TimeUnit.SECONDS)
                }
                return route(request)
            }
        }
        createViewModel()
        awaitLoaded()

        val salary = viewModel.uiState.value.incomes.first { it.id == 1 }
        viewModel.toggleSkip(salary)
        viewModel.toggleSkip(salary)
        release.countDown()
        awaitState {
            it.togglingId == null &&
                it.incomes.first { i -> i.id == 1 }.next_skip_action == SkipAction.UNSKIP
        }

        // One press, one toggle: the second tap could not flip the state
        // twice (skip then un-skip).
        assertEquals(1, skipToggleCalls().size)
    }

    @Test
    fun `a failed toggle keeps the rows and shows the web message`() = runBlocking {
        seed(
            incomeDto(1, "Salary", nextDue = "2026-09-01", backlog = 1, overdue = true),
            incomeDto(2, "Rent", nextDue = "2026-09-05"),
        )
        createViewModel()
        awaitLoaded()

        toggleStatus = 500
        viewModel.toggleSkip(viewModel.uiState.value.incomes.first { it.id == 1 })
        awaitState { it.actionError != null }

        assertEquals("Could not update your recurring incomes.", viewModel.uiState.value.actionError)
        assertNull(viewModel.uiState.value.togglingId)
        // The held rows stay on screen — only the action failed.
        assertEquals(listOf(1, 2), viewModel.uiState.value.incomes.map { it.id })
        assertEquals(1, viewModel.uiState.value.incomes.first { it.id == 1 }.backlog_count)

        // The next press clears the message and works.
        toggleStatus = 200
        skipScripts[1] = ArrayDeque(
            listOf(
                incomeDto(1, "Salary", backlog = 0, nextSkipAction = SkipAction.UNSKIP),
            ),
        )
        viewModel.toggleSkip(viewModel.uiState.value.incomes.first { it.id == 1 })
        awaitState {
            it.actionError == null &&
                it.incomes.first { i -> i.id == 1 }.next_skip_action == SkipAction.UNSKIP
        }
        // Both presses hit the wire: the failed one and the successful one.
        assertEquals(2, skipToggleCalls().size)
    }
}
