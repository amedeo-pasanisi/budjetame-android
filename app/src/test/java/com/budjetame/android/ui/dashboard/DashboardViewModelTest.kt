package com.budjetame.android.ui.dashboard

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.data.api.CategorySliceDto
import com.budjetame.android.data.api.DashboardApi
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.MonthBucketDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.dashboard.ApiDashboardRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Dashboard flow tested at the single seam (the HTTP API): the ViewModel
 * is driven through the real repository, Retrofit, OkHttp, and a
 * MockWebServer whose dispatcher is a small stateful fake of the
 * /dashboard/summary resource — echoing the requested month back, so the
 * pie month picker and the stale-response guard (US27) are observable on
 * the wire.
 * The month clock is injected for determinism.
 */
class DashboardViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(
        val method: String,
        val path: String,
        val month: String?,
        val from: String? = null,
        val to: String? = null,
    )

    private lateinit var server: MockWebServer
    private lateinit var viewModel: DashboardViewModel

    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    /** Month → latch: a gated month's response is held back (a slow wire). */
    private val heldMonths = ConcurrentHashMap<String, CountDownLatch>()
    /** kind:from:to → latch: a gated trend response is held back. */
    private val heldTrends = ConcurrentHashMap<String, CountDownLatch>()
    private val failMonths = mutableSetOf<String>()
    private var failBudget = false
    private var failTrend = false
    /** kind:month → amount: overrides a trend bucket's zero default. */
    private val trendAmounts = mutableMapOf<String, String>()
    private var budgetSpendableToday = "49.80"
    private var now: YearMonth = YearMonth.of(2026, 8)

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        calls.clear()
        heldMonths.clear()
        heldTrends.clear()
        failMonths.clear()
        failBudget = false
        failTrend = false
        trendAmounts.clear()
        budgetSpendableToday = "49.80"
        now = YearMonth.of(2026, 8)
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
        val repository = ApiDashboardRepository(client.create(DashboardApi::class.java))
        viewModel = DashboardViewModel(repository, currentMonth = { now })
    }

    private fun route(request: RecordedRequest): MockResponse {
        val method = request.method ?: "GET"
        val path = request.requestUrl?.encodedPath ?: request.path.orEmpty()
        val month = request.requestUrl?.queryParameter("month")
        val from = request.requestUrl?.queryParameter("from_month")
        val to = request.requestUrl?.queryParameter("to_month")
        calls.add(RecordedCall(method, path, month, from, to))

        return when {
            method == "GET" && path == "/api/dashboard/summary" -> {
                // A gated month simulates a slow response: the test
                // navigates away before it arrives (US27).
                heldMonths[month]?.await(5, TimeUnit.SECONDS)
                when {
                    month in failMonths -> jsonResponse(500, """{"detail":"boom"}""")
                    else -> jsonResponse(200, json.encodeToString(summaryFor(month.orEmpty())))
                }
            }

            method == "GET" && path == "/api/dashboard/budget" -> {
                if (failBudget) jsonResponse(500, """{"detail":"boom"}""")
                else jsonResponse(200, json.encodeToString(budgetFor()))
            }

            method == "GET" &&
                (path == "/api/dashboard/expense-trend" || path == "/api/dashboard/income-trend") -> {
                val kind = if (path == "/api/dashboard/expense-trend") "expense" else "income"
                // A gated range simulates a slow response: the test moves
                // the pickers before it arrives.
                heldTrends["$kind:$from:$to"]?.await(5, TimeUnit.SECONDS)
                if (failTrend) jsonResponse(500, """{"detail":"boom"}""")
                else jsonResponse(200, json.encodeToString(trendFor(kind, from.orEmpty(), to.orEmpty())))
            }

            else -> MockResponse().setResponseCode(404)
        }
    }

    /** A summary that echoes its month — the fake of GET /dashboard/summary.
     * The slices sum to the month's totals, as the real endpoint guarantees. */
    private fun summaryFor(month: String) = DashboardSummaryDto(
        net_worth = "1234.56",
        month = month,
        income = "200.00",
        expenses = "150.00",
        expenses_by_category = listOf(
            CategorySliceDto(
                category_id = 1,
                name = "Food",
                icon = "🍕",
                color = "#ef4444",
                amount = "100.00",
            ),
            CategorySliceDto(
                category_id = null,
                name = "Uncategorized",
                icon = null,
                color = null,
                amount = "50.00",
            ),
        ),
        incomes_by_category = listOf(
            CategorySliceDto(
                category_id = 2,
                name = "Salary",
                icon = "💰",
                color = "#10b981",
                amount = "200.00",
            ),
        ),
    )

    private fun jsonResponse(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    /** The fake of GET /dashboard/budget: the current month's frame, raw —
     * spendable_today overridable so the negative case is observable. */
    private fun budgetFor() = BudgetDto(
        month = "2026-08",
        monthly_spendable = "500.00",
        daily_allowance = "16.60",
        spendable_today = budgetSpendableToday,
    )

    /** The fake of the two trend endpoints: one bucket per month in the
     * inclusive range, oldest first, zero-filled unless overridden (the
     * real endpoint's guarantee, T12/US28). */
    private fun trendFor(kind: String, from: String, to: String): TrendDto {
        val buckets = mutableListOf<MonthBucketDto>()
        var month = YearMonth.parse(from)
        val end = YearMonth.parse(to)
        while (!month.isAfter(end)) {
            buckets += MonthBucketDto(
                month = month.toString(),
                amount = trendAmounts["$kind:$month"] ?: "0.00",
            )
            month = month.plusMonths(1)
        }
        return TrendDto(from_month = from, to_month = to, months = buckets)
    }

    /** The trend wire calls as (kind, from, to) triples, in request order. */
    private fun trendCalls(): List<Triple<String, String, String>> =
        calls.mapNotNull { call ->
            when (call.path) {
                "/api/dashboard/expense-trend", "/api/dashboard/income-trend" -> Triple(
                    call.path.removeSuffix("-trend").substringAfterLast('/'),
                    call.from.orEmpty(),
                    call.to.orEmpty(),
                )

                else -> null
            }
        }

    private fun budgetCalls(): Int = calls.count { it.path == "/api/dashboard/budget" }

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
    }

    /** Waits until the initial load of every card has settled (loaded or
     * failed): the reload fetches sequentially, so the summary alone
     * resolving does not mean the budget and trend responses arrived. */
    private suspend fun awaitDashboard() {
        awaitLoaded()
        withTimeout(5_000) { viewModel.uiState.first { it.budget != null || it.budgetError != null } }
        withTimeout(5_000) { viewModel.uiState.first { it.trend != null || it.trendError != null } }
    }

    private suspend fun awaitState(predicate: (DashboardViewModel.UiState) -> Boolean) {
        withTimeout(5_000) { viewModel.uiState.first(predicate) }
    }

    private fun months(): List<String> = calls.toList().mapNotNull { it.month }

    // --- The summary response mapping (the ticket's seam-test criterion) ---

    @Test
    fun `the summary response maps every field through the seam`() = runBlocking {
        createViewModel()
        awaitLoaded()

        val state = viewModel.uiState.value
        val summary = requireNotNull(state.summary)
        assertEquals("1234.56", summary.net_worth)
        assertEquals("2026-08", summary.month)
        assertEquals("200.00", summary.income)
        assertEquals("150.00", summary.expenses)

        val food = summary.expenses_by_category.first { it.name == "Food" }
        assertEquals(1, food.category_id)
        assertEquals("🍕", food.icon)
        assertEquals("#ef4444", food.color)
        assertEquals("100.00", food.amount)

        // The Uncategorized slice (a deleted Category): the backend sends
        // nulls, the client keeps them — the screen renders a neutral color.
        val uncategorized = summary.expenses_by_category.first { it.name == "Uncategorized" }
        assertNull(uncategorized.category_id)
        assertNull(uncategorized.icon)
        assertNull(uncategorized.color)
        assertEquals("50.00", uncategorized.amount)

        assertEquals("Salary", summary.incomes_by_category.single().name)

        // The initial load asks for the injected current month.
        assertEquals(listOf("2026-08"), months())
    }

    // --- The pie card's month picker (web parity) ---

    @Test
    fun `a month pick drives the requested month and the held summary`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.onPieMonthChange(YearMonth.of(2026, 7))
        awaitState { it.summary?.month == "2026-07" && it.monthInSync }

        viewModel.onPieMonthChange(YearMonth.of(2026, 8))
        awaitState { it.summary?.month == "2026-08" }
        viewModel.onPieMonthChange(YearMonth.of(2026, 9))
        awaitState { it.summary?.month == "2026-09" }

        assertEquals(listOf("2026-08", "2026-07", "2026-08", "2026-09"), months())
        assertEquals("2026-09", viewModel.uiState.value.summary?.month)
        assertTrue(viewModel.uiState.value.monthInSync)
    }

    @Test
    fun `the month picker lands on any month in one pick`() = runBlocking {
        createViewModel()
        awaitLoaded()

        // The dialog (unlike the old arrows) jumps straight to any month —
        // no neighbouring months are fetched along the way.
        viewModel.onPieMonthChange(YearMonth.of(2025, 3))
        awaitState { it.summary?.month == "2025-03" && it.monthInSync }

        assertEquals(listOf("2026-08", "2025-03"), months())
    }

    @Test
    fun `a month pick crosses the year boundary`() = runBlocking {
        now = YearMonth.of(2026, 1)
        createViewModel()
        awaitLoaded()

        viewModel.onPieMonthChange(YearMonth.of(2025, 12))
        awaitState { it.summary?.month == "2025-12" }

        assertEquals(listOf("2026-01", "2025-12"), months())
    }

    @Test
    fun `a month change refetches the summary alone`() = runBlocking {
        createViewModel()
        awaitDashboard()

        viewModel.onPieMonthChange(YearMonth.of(2026, 9))
        awaitState { it.summary?.month == "2026-09" && it.monthInSync }

        // The Budget is current-month-only and the trend has its own range:
        // a pie-month change never refetches them (the web app's effects).
        assertEquals(listOf("2026-08", "2026-09"), months())
        assertEquals(1, budgetCalls())
        assertEquals(1, trendCalls().size)
    }

    // --- The pie toggle ---

    @Test
    fun `the pie side toggles between the response's two pies`() = runBlocking {
        createViewModel()
        awaitDashboard()

        assertEquals(PieSide.EXPENSE, viewModel.uiState.value.pieSide)
        assertEquals(
            listOf("Food", "Uncategorized"),
            viewModel.uiState.value.pieSlices.map { it.name },
        )
        assertEquals("150.00", viewModel.uiState.value.pieTotal)

        viewModel.onPieSideChange(PieSide.INCOME)
        assertEquals(PieSide.INCOME, viewModel.uiState.value.pieSide)
        assertEquals(listOf("Salary"), viewModel.uiState.value.pieSlices.map { it.name })
        assertEquals("200.00", viewModel.uiState.value.pieTotal)

        // The toggle is pure state: both pies arrive in one summary response,
        // so no extra request goes out — the initial load's summary, budget,
        // and trend fetches stay untouched.
        assertEquals(3, calls.size)
    }

    // --- Load failure + retry ---

    @Test
    fun `a load failure shows the web's exact error and retry refetches`() = runBlocking {
        failMonths.add("2026-08")
        createViewModel()
        awaitLoaded()

        assertEquals("Could not load your dashboard.", viewModel.uiState.value.loadError)
        assertNull(viewModel.uiState.value.summary)

        failMonths.clear()
        viewModel.retry()
        awaitState { it.summary != null && it.loadError == null }

        assertEquals("1234.56", viewModel.uiState.value.summary?.net_worth)
    }

    @Test
    fun `a failed month change reverts the selector to the held month`() = runBlocking {
        createViewModel()
        awaitLoaded()
        failMonths.add("2026-07")

        viewModel.onPieMonthChange(YearMonth.of(2026, 7))
        awaitState { it.requestedMonth == YearMonth.of(2026, 7) }
        awaitState { it.requestedMonth == YearMonth.of(2026, 8) }

        // The held summary keeps its month, the selector returned to it —
        // the screen never sits stuck on a "Loading…" under a month whose
        // data never arrived.
        assertEquals("2026-08", viewModel.uiState.value.summary?.month)
        assertTrue(viewModel.uiState.value.monthInSync)
        assertNull(viewModel.uiState.value.loadError)
    }

    @Test
    fun `a late response for a superseded month never overwrites the current one`() = runBlocking {
        val holdJuly = CountDownLatch(1)
        heldMonths["2026-07"] = holdJuly
        createViewModel()
        awaitLoaded()

        viewModel.onPieMonthChange(YearMonth.of(2026, 7))
        awaitState { it.requestedMonth == YearMonth.of(2026, 7) }
        viewModel.onPieMonthChange(YearMonth.of(2026, 8))
        awaitState { it.requestedMonth == YearMonth.of(2026, 8) && it.summary?.month == "2026-08" }

        // The July response finally arrives — long after the user moved on.
        holdJuly.countDown()

        // If the guard were missing, the stale July summary would land and
        // either replace the summary or desync the month. Neither happens.
        val wrong = withTimeoutOrNull(5_000) {
            viewModel.uiState.first { !it.monthInSync || it.summary?.month == "2026-07" }
        }
        assertNull(wrong)
        assertEquals("2026-08", viewModel.uiState.value.summary?.month)
        assertTrue(viewModel.uiState.value.monthInSync)
    }

    // --- ADR-0002 background refetch ---

    @Test
    fun `a write elsewhere refetches summary, budget, and trend in the background`() = runBlocking {
        createViewModel()
        awaitDashboard()
        assertEquals(1, months().size)
        assertEquals(1, budgetCalls())
        assertEquals(1, trendCalls().size)

        // ADR-0002: the transport bumps the data version after a successful
        // write anywhere; the screen re-fetches every card in the
        // background (the refetched states equal the held ones, so the
        // request counts are the observable).
        DataVersion.bump()
        withTimeout(5_000) {
            while (months().size < 2 || budgetCalls() < 2 || trendCalls().size < 2) {
                delay(10)
            }
        }

        assertEquals(listOf("2026-08", "2026-08"), months())
        assertEquals(2, budgetCalls())
        assertEquals(
            listOf(
                Triple("expense", "2026-03", "2026-08"),
                Triple("expense", "2026-03", "2026-08"),
            ),
            trendCalls(),
        )
    }

    // --- The Budget card (web issue #65, ADR-0012 semantics) ---

    @Test
    fun `the budget response maps every field through the seam`() = runBlocking {
        createViewModel()
        awaitDashboard()

        val budget = requireNotNull(viewModel.uiState.value.budget)
        assertEquals("2026-08", budget.month)
        assertEquals("500.00", budget.monthly_spendable)
        assertEquals("16.60", budget.daily_allowance)
        assertEquals("49.80", budget.spendable_today)

        // The Budget is current-month-only by product decision: no month
        // parameter ever goes out.
        val call = calls.first { it.path == "/api/dashboard/budget" }
        assertEquals("GET", call.method)
        assertNull(call.month)
    }

    @Test
    fun `a negative spendable today arrives raw for the card to floor`() = runBlocking {
        budgetSpendableToday = "-12.34"
        createViewModel()
        awaitDashboard()

        // Raw through the seam: the card renders it as 0 with the "you're
        // over" note; the ViewModel never rewrites the endpoint's value.
        assertEquals("-12.34", viewModel.uiState.value.budget?.spendable_today)
    }

    @Test
    fun `a failed budget load surfaces its error and a refetch clears it`() = runBlocking {
        failBudget = true
        createViewModel()
        awaitDashboard()

        assertEquals("Could not load the budget.", viewModel.uiState.value.budgetError)
        assertNull(viewModel.uiState.value.budget)

        failBudget = false
        DataVersion.bump()
        awaitState { it.budget != null && it.budgetError == null }
    }

    // --- The trend chart (T12, US28) ---

    @Test
    fun `the trend loads the expense buckets of the default range oldest first`() = runBlocking {
        createViewModel()
        awaitDashboard()

        val trend = requireNotNull(viewModel.uiState.value.trend)
        assertEquals(TrendKind.EXPENSE, trend.kind)
        assertEquals("2026-03", trend.data.from_month)
        assertEquals("2026-08", trend.data.to_month)
        assertEquals(
            listOf("2026-03", "2026-04", "2026-05", "2026-06", "2026-07", "2026-08"),
            trend.data.months.map { it.month },
        )
        // Zero-filled for months with nothing recorded.
        assertTrue(trend.data.months.all { it.amount == "0.00" })
        assertTrue(viewModel.uiState.value.trendInSync)

        // The default range is the current month minus five months through
        // the current month, asked of the expense endpoint.
        assertEquals(listOf(Triple("expense", "2026-03", "2026-08")), trendCalls())
    }

    @Test
    fun `the trend toggle switches endpoints and loads the income buckets`() = runBlocking {
        trendAmounts["income:2026-03"] = "77.00"
        createViewModel()
        awaitDashboard()
        assertTrue(viewModel.uiState.value.trendInSync)

        viewModel.onTrendKindChange(TrendKind.INCOME)
        awaitState { it.trend?.kind == TrendKind.INCOME && it.trendInSync }

        val trend = requireNotNull(viewModel.uiState.value.trend)
        assertEquals("77.00", trend.data.months.first { it.month == "2026-03" }.amount)
        assertEquals(
            listOf(
                Triple("expense", "2026-03", "2026-08"),
                Triple("income", "2026-03", "2026-08"),
            ),
            trendCalls(),
        )
    }

    @Test
    fun `the trend range pickers drive the wire and swapping keeps from before to`() = runBlocking {
        createViewModel()
        awaitDashboard()

        viewModel.onTrendFromChange(YearMonth.of(2026, 5))
        awaitState { it.trendFrom == YearMonth.of(2026, 5) && it.trendInSync }

        // From after To swaps the two instead of a reversed request: the
        // user's intent was a range between the two months (T12).
        viewModel.onTrendFromChange(YearMonth.of(2026, 10))
        awaitState {
            it.trendFrom == YearMonth.of(2026, 8) &&
                it.trendTo == YearMonth.of(2026, 10) && it.trendInSync
        }

        // To before From swaps too.
        viewModel.onTrendToChange(YearMonth.of(2026, 1))
        awaitState {
            it.trendFrom == YearMonth.of(2026, 1) &&
                it.trendTo == YearMonth.of(2026, 8) && it.trendInSync
        }

        // A To inside the range only moves the end.
        viewModel.onTrendToChange(YearMonth.of(2026, 6))
        awaitState { it.trendTo == YearMonth.of(2026, 6) && it.trendInSync }

        assertEquals(
            listOf(
                Triple("expense", "2026-03", "2026-08"),
                Triple("expense", "2026-05", "2026-08"),
                Triple("expense", "2026-08", "2026-10"),
                Triple("expense", "2026-01", "2026-08"),
                Triple("expense", "2026-01", "2026-06"),
            ),
            trendCalls(),
        )
    }

    @Test
    fun `a failed trend load surfaces its error and a refetch clears it`() = runBlocking {
        failTrend = true
        createViewModel()
        awaitDashboard()

        assertEquals("Could not load the trend.", viewModel.uiState.value.trendError)
        assertNull(viewModel.uiState.value.trend)

        failTrend = false
        DataVersion.bump()
        awaitState { it.trend != null && it.trendError == null }
    }

    @Test
    fun `a late trend response never overwrites the current range`() = runBlocking {
        val holdIncome = CountDownLatch(1)
        heldTrends["income:2026-03:2026-08"] = holdIncome
        createViewModel()
        awaitDashboard()

        viewModel.onTrendKindChange(TrendKind.INCOME)
        awaitState { it.trendKind == TrendKind.INCOME }
        // The range moves before the gated income response arrives.
        viewModel.onTrendFromChange(YearMonth.of(2026, 4))
        awaitState { it.trendFrom == YearMonth.of(2026, 4) && it.trendInSync }

        // The gated 2026-03 income response finally arrives — long after the
        // user moved the range.
        holdIncome.countDown()

        // If the guard were missing, the stale response would overwrite the
        // loaded trend and desync it from the requested range. Neither
        // happens.
        val wrong = withTimeoutOrNull(5_000) {
            viewModel.uiState.first { !it.trendInSync || it.trend?.data?.from_month == "2026-03" }
        }
        assertNull(wrong)
        assertEquals("2026-04", viewModel.uiState.value.trend?.data?.from_month)
        assertTrue(viewModel.uiState.value.trendInSync)
    }
}
