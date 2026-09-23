package com.budjetame.android.ui.dashboard

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Budget card (web issues #65, #100) through the real DashboardScreen:
 * the big Spendable Today, the frame line "€Y this month (€X per day)" with
 * the "€X left this month" line under it, the red "€X over today's budget"
 * note when only the bucket is negative, the month's red bottom line
 * superseding the bucket note ("€X over this month's budget") when the whole
 * frame is blown, and the hide rule (web issue #66): the card hides only
 * once both Recurring lists have loaded and proved empty, and a failed list
 * keeps it visible. The card's pure text rules are pinned by
 * BudgetDisplayTest; this test pins that the screen renders them.
 */
@RunWith(AndroidJUnit4::class)
class BudgetCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchDashboard(
        budget: BudgetDto = budgetDto(),
        costs: RecurringCostGateway = RecurringCostsFixture(listOf(rentDefinition)),
        incomes: RecurringIncomeGateway = RecurringIncomesFixture(emptyList()),
    ) {
        composeRule.setContent {
            DashboardScreen(
                dashboard = DashboardFixtureGateway(budget),
                recurringCosts = costs,
                recurringIncomes = incomes,
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("NET WORTH").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitBudgetCard() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("SPENDABLE TODAY").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_card_shows_the_frame_line_and_the_amount_left_this_month() {
        launchDashboard()
        awaitBudgetCard()

        composeRule.onNodeWithText("€49.80").assertIsDisplayed()
        composeRule.onNodeWithText("€500.00 this month (€16.60 per day)").assertIsDisplayed()
        composeRule.onNodeWithText("€333.40 left this month").assertIsDisplayed()
    }

    @Test
    fun a_negative_bucket_floors_the_big_number_and_keeps_the_bucket_note() {
        launchDashboard(budget = budgetDto(spendableToday = "-12.34", remaining = "300.00"))
        awaitBudgetCard()

        composeRule.onNodeWithText("€0.00").assertIsDisplayed()
        composeRule.onNodeWithText("€12.34 over today's budget").assertIsDisplayed()
        composeRule.onNodeWithText("€300.00 left this month").assertIsDisplayed()
    }

    @Test
    fun a_negative_month_supersedes_the_bucket_note() {
        launchDashboard(budget = budgetDto(spendableToday = "-433.40", remaining = "-100.00"))
        awaitBudgetCard()

        // The month's bottom line replaces the bucket's transient note:
        // never two over-notes at once, and no "left" line.
        composeRule.onNodeWithText("€0.00").assertIsDisplayed()
        composeRule.onNodeWithText("€100.00 over this month's budget").assertIsDisplayed()
        composeRule.onAllNodesWithText("€433.40 over today's budget").assertCountEquals(0)
        composeRule.onAllNodesWithText("left this month", substring = true).assertCountEquals(0)
    }

    @Test
    fun the_card_hides_once_both_recurring_lists_load_empty() {
        val gate = CompletableDeferred<Unit>()
        launchDashboard(
            costs = RecurringCostsFixture(emptyList(), gate = gate),
            incomes = RecurringIncomesFixture(emptyList(), gate = gate),
        )
        // Unknown definitions (both lists still on the wire) keep the card
        // visible; the moment they resolve empty, the card goes — and the
        // card's data must not linger once hidden.
        awaitBudgetCard()

        gate.complete(Unit)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("SPENDABLE TODAY").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("€49.80").assertDoesNotExist()
    }

    @Test
    fun a_failed_definitions_check_keeps_the_card_visible() {
        launchDashboard(
            costs = RecurringCostsFixture(listOf(rentDefinition), fail = true),
            incomes = RecurringIncomesFixture(emptyList(), fail = true),
        )
        awaitBudgetCard()

        // A failed load must never look like an empty Budget.
        composeRule.onNodeWithText("€49.80").assertIsDisplayed()
    }
}

private fun budgetDto(
    spendableToday: String = "49.80",
    remaining: String = "333.40",
) = BudgetDto(
    month = "2026-08",
    monthly_spendable = "500.00",
    recurring_incomes_total = "500.00",
    recurring_costs_total = "0.00",
    daily_allowance = "16.60",
    spendable_today = spendableToday,
    remaining_monthly_spendable = remaining,
)

private val rentDefinition = RecurringCostDto(
    id = 1,
    name = "Rent",
    amount = "800.00",
    interval_value = 1,
    interval_unit = IntervalUnit.MONTHS,
    start_date = "2026-08-01",
    next_due_date = "2026-09-01",
    next_unpaid_occurrence_date = "2026-09-01",
    backlog_count = 0,
    created_at = "2026-08-01T10:00:00Z",
)

/** The in-memory dashboard: a loaded summary and the test's budget, and an
 * empty trend (only the Budget card is under test). */
private class DashboardFixtureGateway(private val budget: BudgetDto) : DashboardGateway {

    override suspend fun fetchSummary(month: String): DashboardSummaryDto =
        DashboardSummaryDto(
            net_worth = "800.00",
            month = month,
            income = "0.00",
            expenses = "0.00",
            expenses_by_category = emptyList(),
            incomes_by_category = emptyList(),
        )

    override suspend fun fetchTrend(kind: TrendKind, fromMonth: String, toMonth: String): TrendDto =
        TrendDto(from_month = fromMonth, to_month = toMonth, months = emptyList())

    override suspend fun fetchBudget(month: String?): BudgetDto = budget
}

/** The Costs side of the hide rule's two lists: instant, settable, and
 * optionally gated (the gate holds the response on the wire). */
private class RecurringCostsFixture(
    private val definitions: List<RecurringCostDto>,
    private val fail: Boolean = false,
    private val gate: CompletableDeferred<Unit>? = null,
) : RecurringCostGateway {

    override suspend fun fetchRecurringCosts(includeFrozen: Boolean): List<RecurringCostDto> {
        gate?.await()
        if (fail) throw IllegalStateException("network down")
        return definitions
    }

    override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto =
        error("unused")

    override suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto =
        error("unused")

    override suspend fun freezeRecurringCost(id: Int): RecurringCostDto = error("unused")

    override suspend fun unfreezeRecurringCost(id: Int): RecurringCostDto = error("unused")

    override suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto> = error("unused")

    override suspend fun setOccurrenceSkipped(
        id: Int,
        occurrenceDate: String,
        skipped: Boolean,
    ): List<RecurringOccurrenceDto> = error("unused")
}

/** The Incomes mirror of [RecurringCostsFixture]. */
private class RecurringIncomesFixture(
    private val definitions: List<RecurringIncomeDto>,
    private val fail: Boolean = false,
    private val gate: CompletableDeferred<Unit>? = null,
) : RecurringIncomeGateway {

    override suspend fun fetchRecurringIncomes(includeFrozen: Boolean): List<RecurringIncomeDto> {
        gate?.await()
        if (fail) throw IllegalStateException("network down")
        return definitions
    }

    override suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto =
        error("unused")

    override suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto =
        error("unused")

    override suspend fun freezeRecurringIncome(id: Int): RecurringIncomeDto = error("unused")

    override suspend fun unfreezeRecurringIncome(id: Int): RecurringIncomeDto = error("unused")

    override suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto> = error("unused")

    override suspend fun setOccurrenceSkipped(
        id: Int,
        occurrenceDate: String,
        skipped: Boolean,
    ): List<RecurringOccurrenceDto> = error("unused")
}
