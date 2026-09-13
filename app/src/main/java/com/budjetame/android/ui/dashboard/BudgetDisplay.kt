package com.budjetame.android.ui.dashboard

import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.util.Money

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
 * response — all strings formatted, all precedence rules applied. */
data class BudgetCardText(
    /** The big number: Spendable Today floored at 0 while the bucket is
     * negative — future accruals repay the debt (ADR-0012 semantics). */
    val spendableToday: String,
    /** The frame line: `€Y this month (€A income − €B costs)`. */
    val frameLine: String,
    /** The daily allowance line: `€X per day`. */
    val dailyLine: String,
    /** The bucket's over-note, or null: it shows while the bucket is
     * negative, but gives way to the month's bottom line once the frame
     * itself is blown. */
    val bucketNote: String?,
    /** `€X left this month`, or the month's overage line. */
    val remainingLine: String,
    /** True when [remainingLine] is the error case (the frame is blown). */
    val remainingOver: Boolean,
)

fun budgetCardText(budget: BudgetDto): BudgetCardText {
    val bucketNegative = budget.spendable_today.startsWith("-")
    val monthNegative = budget.remaining_monthly_spendable.startsWith("-")
    return BudgetCardText(
        spendableToday = if (bucketNegative) "0.00" else budget.spendable_today,
        frameLine = "${Money.formatEuros(budget.monthly_spendable)} this month " +
            "(${Money.formatEuros(budget.recurring_incomes_total)} income \u2212 " +
            "${Money.formatEuros(budget.recurring_costs_total)} costs)",
        dailyLine = "${Money.formatEuros(budget.daily_allowance)} per day",
        bucketNote = if (bucketNegative && !monthNegative) {
            "${Money.formatEuros(budget.spendable_today.drop(1))} over today's budget"
        } else {
            null
        },
        remainingLine = if (monthNegative) {
            "${Money.formatEuros(budget.remaining_monthly_spendable.drop(1))} " +
                "over this month's budget"
        } else {
            "${Money.formatEuros(budget.remaining_monthly_spendable)} left this month"
        },
        remainingOver = monthNegative,
    )
}
