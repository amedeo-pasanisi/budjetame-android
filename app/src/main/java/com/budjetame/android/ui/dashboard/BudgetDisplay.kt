package com.budjetame.android.ui.dashboard

import com.budjetame.android.R
import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.util.Money
import java.util.Locale

/**
 * Presentation-only text rules for the Budget card (web issues #65, #100),
 * ported from the web app's BudgetCard: the big number's floor at 0 while
 * the bucket is negative, the frame line "€Y this month (€A income − €B
 * costs) · €X per day" (Monthly Spendable, its recurring breakdown, then
 * Daily Allowance), the bucket's transient "€X over today's budget" note,
 * and the Remaining Monthly Spendable bottom line — "€X left this month",
 * or the month's red "€X over this month's budget" when the whole frame
 * is blown. A negative Remaining Monthly Spendable supersedes the bucket's
 * note: never two over-notes at once. The client only renders the
 * endpoint's values; it never computes the frame (ADR-0001).
 */

/** Everything the Budget card's body renders for one GET /dashboard/budget
 * response — all amounts formatted, all precedence rules applied. The lines
 * are [UiText] (ADR-0010): the card resolves them in the Account's Locale. */
data class BudgetCardText(
    /** The big number: Spendable Today floored at 0 while the bucket is
     * negative — future accruals repay the debt (ADR-0012 semantics). */
    val spendableToday: String,
    /** The frame line: `€Y this month (€A income − €B costs)`. */
    val frameLine: UiText,
    /** The daily allowance line: `€X per day`. */
    val dailyLine: UiText,
    /** The bucket's over-note, or null: it shows while the bucket is
     * negative, but gives way to the month's bottom line once the frame
     * itself is blown. */
    val bucketNote: UiText?,
    /** `€X left this month`, or the month's overage line. */
    val remainingLine: UiText,
    /** True when [remainingLine] is the error case (the frame is blown). */
    val remainingOver: Boolean,
)

fun budgetCardText(budget: BudgetDto, locale: Locale = Locale.US): BudgetCardText {
    val bucketNegative = budget.spendable_today.startsWith("-")
    val monthNegative = budget.remaining_monthly_spendable.startsWith("-")
    fun euros(amount: String) = Money.formatEuros(amount, locale)
    return BudgetCardText(
        spendableToday = if (bucketNegative) "0.00" else budget.spendable_today,
        frameLine = UiText.of(
            R.string.dashboard_budget_frame_line,
            euros(budget.monthly_spendable),
            euros(budget.recurring_incomes_total),
            euros(budget.recurring_costs_total),
        ),
        dailyLine = UiText.of(R.string.dashboard_budget_per_day, euros(budget.daily_allowance)),
        bucketNote = if (bucketNegative && !monthNegative) {
            UiText.of(R.string.dashboard_budget_over_today, euros(budget.spendable_today.drop(1)))
        } else {
            null
        },
        remainingLine = if (monthNegative) {
            UiText.of(R.string.dashboard_budget_over_month, euros(budget.remaining_monthly_spendable.drop(1)))
        } else {
            UiText.of(R.string.dashboard_budget_left_month, euros(budget.remaining_monthly_spendable))
        },
        remainingOver = monthNegative,
    )
}
