package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto

/**
 * Presentation-only logic for the Recurring Costs screen and form (ticket
 * #22), ported from the web app's recurringCosts.ts + RecurringCostForm.tsx:
 * the interval display text, the due-date override's shape per interval
 * unit, and the next-due ordering. These are the cheap spots where porting
 * bugs hide, so they get direct JVM tests.
 */

/** The unit options the form offers, in the web app's order. */
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
 * place), shared later with the Recurring Incomes side.
 */
fun sortRecurringCostsByNextDue(costs: List<RecurringCostDto>): List<RecurringCostDto> =
    costs.sortedWith(
        compareBy<RecurringCostDto> { it.next_due_date }.thenBy { it.name.lowercase() },
    )
