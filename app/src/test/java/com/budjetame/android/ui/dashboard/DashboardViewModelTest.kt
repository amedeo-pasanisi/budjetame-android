package com.budjetame.android.ui.dashboard

import com.budjetame.android.MainDispatcherRule
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.CategorySliceDto
import com.budjetame.android.data.api.DashboardApi
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.DataVersion
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
 * /dashboard/summary resource — echoing the requested month back, so month
 * navigation and the stale-response guard (US27) are observable on the wire.
 * The month clock is injected for determinism.
 */
class DashboardViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private data class RecordedCall(val method: String, val path: String, val month: String?)

    private lateinit var server: MockWebServer
    private lateinit var viewModel: DashboardViewModel

    private val calls = ConcurrentLinkedQueue<RecordedCall>()
    /** Month → latch: a gated month's response is held back (a slow wire). */
    private val heldMonths = ConcurrentHashMap<String, CountDownLatch>()
    private val failMonths = mutableSetOf<String>()
    private var now: YearMonth = YearMonth.of(2026, 8)

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        calls.clear()
        heldMonths.clear()
        failMonths.clear()
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
        calls.add(RecordedCall(method, path, month))

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

    private suspend fun awaitLoaded() {
        withTimeout(5_000) { viewModel.uiState.first { !it.loading } }
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

    // --- Month navigation ---

    @Test
    fun `month navigation drives the request month and the held summary`() = runBlocking {
        createViewModel()
        awaitLoaded()

        viewModel.previousMonth()
        awaitState { it.summary?.month == "2026-07" && it.monthInSync }

        viewModel.nextMonth()
        awaitState { it.summary?.month == "2026-08" }
        viewModel.nextMonth()
        awaitState { it.summary?.month == "2026-09" }

        assertEquals(listOf("2026-08", "2026-07", "2026-08", "2026-09"), months())
        assertEquals("2026-09", viewModel.uiState.value.summary?.month)
        assertTrue(viewModel.uiState.value.monthInSync)
    }

    @Test
    fun `previous month crosses the year boundary`() = runBlocking {
        now = YearMonth.of(2026, 1)
        createViewModel()
        awaitLoaded()

        viewModel.previousMonth()
        awaitState { it.summary?.month == "2025-12" }

        assertEquals(listOf("2026-01", "2025-12"), months())
    }

    // --- The pie toggle ---

    @Test
    fun `the pie side toggles between the response's two pies`() = runBlocking {
        createViewModel()
        awaitLoaded()

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

        // The toggle is pure state: both pies arrive in one response, so no
        // extra request goes out.
        assertEquals(1, calls.size)
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

        viewModel.previousMonth()
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
    fun `a late response for a previous month never overwrites the current one`() = runBlocking {
        val holdJuly = CountDownLatch(1)
        heldMonths["2026-07"] = holdJuly
        createViewModel()
        awaitLoaded()

        viewModel.previousMonth()
        awaitState { it.requestedMonth == YearMonth.of(2026, 7) }
        viewModel.nextMonth()
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
    fun `a write elsewhere refetches the current month in the background`() = runBlocking {
        createViewModel()
        awaitLoaded()
        assertEquals(1, months().size)

        // ADR-0002: the transport bumps the data version after a successful
        // write anywhere; the screen re-fetches the same month in the
        // background (the refetched state equals the held one, so the
        // request count is the observable).
        DataVersion.bump()
        withTimeout(5_000) {
            while (months().size < 2) {
                delay(10)
            }
        }

        assertEquals(listOf("2026-08", "2026-08"), months())
    }
}
