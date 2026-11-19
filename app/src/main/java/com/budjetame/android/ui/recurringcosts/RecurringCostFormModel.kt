package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringDefinition
import com.budjetame.android.ui.common.FieldErrors
import com.budjetame.android.ui.common.amountFieldError

/**
 * Presentation-only logic for the Recurring Costs screen and form (ticket
 * #22), ported from the web app's recurringCosts.ts + RecurringCostForm.tsx:
 * the interval display text, the interval-unit select labels, and the
 * next-due ordering. These are the
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

/** The Recurring Cost form's Field Error keys (ADR-0009): one per field a
 * message can render under. */
object RecurringCostFields {
    const val NAME = "name"
    const val AMOUNT = "amount"
    const val INTERVAL = "interval"
    const val START_DATE = "start"
}

private val WHOLE_NUMBER = Regex("""^\d+$""")

/**
 * Submit-and-validate (ADR-0009, mirror of the web
 * RecurringDefinitionForm's `validate()`): one Field Error per wrong field
 * of [form], empty when it may save. The amount is a tolerant Amount Input;
 * the interval must be a whole number of units, at least 1 — empty,
 * fractional, signed or below 1 is one message, like the web's `^\d+$`
 * check. The start date is mandatory only while editing (ADR-0024): at
 * creation an empty one stays allowed, the first Occurrence becoming today.
 */
fun validateRecurringCostForm(form: RecurringCostModalState): FieldErrors = buildMap {
    if (form.name.isBlank()) put(RecurringCostFields.NAME, "Enter a name")
    amountFieldError(form.amount)?.let { put(RecurringCostFields.AMOUNT, it) }
    val interval = form.intervalValue.trim().takeIf { WHOLE_NUMBER.matches(it) }?.toIntOrNull()
    if (interval == null || interval < 1) {
        put(RecurringCostFields.INTERVAL, "The interval must be at least 1")
    }
    if (form.editing && form.startDate.isBlank()) {
        put(RecurringCostFields.START_DATE, "Choose a start date")
    }
}

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
        compareBy<RecurringDefinition> { it.next_due_date ?: "" }.thenBy { it.name.lowercase() },
    )
