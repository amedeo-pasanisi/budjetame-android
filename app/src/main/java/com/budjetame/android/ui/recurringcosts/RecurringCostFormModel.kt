package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringDefinition
import com.budjetame.android.data.api.SkipAction

/**
 * Presentation-only logic for the Recurring Costs screen and form (ticket
 * #22), ported from the web app's recurringCosts.ts + RecurringCostForm.tsx:
 * the interval display text, the interval-unit select labels, the next-due
 * ordering, and the Skip/Un-skip button's label (ADR-0016). These are the
 * cheap spots where porting bugs hide, so they get direct JVM tests. The
 * reads are type-agnostic and
 * shared with the Recurring Incomes side (web issue #60, ADR-0011), exactly
 * like the web's recurringIncomes.ts re-exporting them from recurringCosts.ts.
 */

/**
 * The unit options the form offers, in the web app's order. Shared with the
 * Recurring Incomes side (web issue #60): the interval reads are the same on
 * both sides, like the web's recurringIncomes.ts re-exporting them from
 * recurringCosts.ts (ADR-0011 leaves the display layer free to reuse pure
 * logic).
 */
val INTERVAL_UNIT_OPTIONS: List<IntervalUnit> =
    listOf(IntervalUnit.DAYS, IntervalUnit.WEEKS, IntervalUnit.MONTHS, IntervalUnit.YEARS)

/** A unit option's label in the interval select — the singular form when
 * the interval value is 1, the plural otherwise ("Month" next to a 1,
 * "Months" next to a 2): the interval row reads "Repeats every N months",
 * the unit turning singular when N is 1, like the web form's option labels
 * (ADR-0024). A null or unparseable value reads plural, the web's
 * fallback.
 */
fun intervalUnitLabel(intervalValue: Int?, unit: IntervalUnit): String = when (unit) {
    IntervalUnit.DAYS -> if (intervalValue == 1) "Day" else "Days"
    IntervalUnit.WEEKS -> if (intervalValue == 1) "Week" else "Weeks"
    IntervalUnit.MONTHS -> if (intervalValue == 1) "Month" else "Months"
    IntervalUnit.YEARS -> if (intervalValue == 1) "Year" else "Years"
}

/**
 * The Skip/Un-skip button's label (ADR-0016), from the definition's
 * `next_skip_action`: "Un-skip" exactly when the press would restore a
 * Skipped Occurrence, "Skip" otherwise. Shared with the Recurring Incomes
 * side (web issue #60) like the interval reads (ADR-0011): the web's
 * RecurringIncomesScreen.tsx reads the same field with the same ternary.
 */
fun skipToggleLabel(action: SkipAction): String =
    if (action == SkipAction.UNSKIP) "Un-skip" else "Skip"

/**
 * The interval as display text — "Every month", "Every 2 weeks", "Every 5
 * days", "Every year" — singular for 1, plural otherwise (the web app's
 * `intervalText`).
 */
fun intervalText(value: Int, unit: IntervalUnit): String = when (unit) {
    IntervalUnit.DAYS -> if (value == 1) "Every day" else "Every $value days"
    IntervalUnit.WEEKS -> if (value == 1) "Every week" else "Every $value weeks"
    IntervalUnit.MONTHS -> if (value == 1) "Every month" else "Every $value months"
    IntervalUnit.YEARS -> if (value == 1) "Every year" else "Every $value years"
}

/** The draft interval value ("2") as an Int, or null when it is not a
 * whole number — the mandatory-interval gate. */
fun parseIntervalValue(raw: String): Int? = raw.trim().toIntOrNull()

/**
 * Definitions ordered by next due date ascending, ties by name
 * case-insensitively — the one order the Recurring screen renders (the
 * backend list arrives sorted too; this keeps locally upserted rows in
 * place). One implementation serves both the Costs and the Incomes sides
 * through the RecurringDefinition fields the ordering reads (web issue
 * #60's shared `sortByNextDue`; the web's recurringIncomes.ts re-exports it).
 */
fun <T : RecurringDefinition> sortByNextDue(definitions: List<T>): List<T> =
    definitions.sortedWith(
        compareBy<RecurringDefinition> { it.next_due_date }.thenBy { it.name.lowercase() },
    )
