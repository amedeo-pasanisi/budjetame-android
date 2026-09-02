package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringDefinition
import com.budjetame.android.data.api.SkipAction

/**
 * Presentation-only logic for the Recurring Costs screen and form (ticket
 * #22), ported from the web app's recurringCosts.ts + RecurringCostForm.tsx:
 * the interval display text, the due-date override's shape per interval
 * unit, the next-due ordering, and the Skip/Un-skip button's label
 * (ADR-0016). These are the cheap spots where porting bugs hide, so they
 * get direct JVM tests. The reads are type-agnostic and
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

/** A unit option's label in the interval select ("Days", "Months", …). */
fun intervalUnitLabel(unit: IntervalUnit): String = when (unit) {
    IntervalUnit.DAYS -> "Days"
    IntervalUnit.WEEKS -> "Weeks"
    IntervalUnit.MONTHS -> "Months"
    IntervalUnit.YEARS -> "Years"
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
 * The due-date override the form sends, shaped to the interval unit
 * (ADR-0010 in the web repo): a day-of-month alone for month intervals, a
 * month+day pair for year intervals, nothing for day/week intervals — a
 * stale override from a unit switch is dropped, never sent (the web form's
 * `buildInput`). Returns (dueDay, dueMonth).
 */
fun dueOverrideFor(
    unit: IntervalUnit,
    dueDay: Int?,
    dueMonth: Int?,
): Pair<Int?, Int?> = when (unit) {
    IntervalUnit.MONTHS -> dueDay to null
    IntervalUnit.YEARS -> if (dueDay != null && dueMonth != null) dueDay to dueMonth else null to null
    else -> null to null
}

/**
 * A year interval's override is a month+day pair: half a pair blocks the
 * save instead of silently dropping the override (web RecurringCostForm).
 * Only meaningful for years — the other units never show the pair.
 */
fun yearOverrideIncomplete(dueDay: Int?, dueMonth: Int?): Boolean =
    (dueDay == null) != (dueMonth == null)

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
