package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.ui.common.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for the Recurring Costs form/screen logic (ticket #22):
 * the interval text, the interval-unit select labels (singular when N is 1),
 * the ordering, and the form gates —
 * ported from the web app's recurringCosts.ts and RecurringCostForm.tsx.
 * The skip-button label is gone with the card button (web ADR-0026).
 * The due-date override is gone (ADR-0024): no helper shapes one anymore. */
class RecurringCostFormModelTest {

    @Test
    fun `interval text is singular for one and plural otherwise`() {
        assertEquals("Every day", intervalText(1, IntervalUnit.DAYS).english())
        assertEquals("Every 5 days", intervalText(5, IntervalUnit.DAYS).english())
        assertEquals("Every week", intervalText(1, IntervalUnit.WEEKS).english())
        assertEquals("Every 2 weeks", intervalText(2, IntervalUnit.WEEKS).english())
        assertEquals("Every month", intervalText(1, IntervalUnit.MONTHS).english())
        assertEquals("Every 3 months", intervalText(3, IntervalUnit.MONTHS).english())
        assertEquals("Every year", intervalText(1, IntervalUnit.YEARS).english())
        assertEquals("Every 10 years", intervalText(10, IntervalUnit.YEARS).english())
    }

    @Test
    fun `interval unit select labels match the web's option order and read singular for one`() {
        assertEquals(
            listOf(IntervalUnit.DAYS, IntervalUnit.WEEKS, IntervalUnit.MONTHS, IntervalUnit.YEARS),
            INTERVAL_UNIT_OPTIONS,
        )
        // Next to a 1 the unit reads singular, plural otherwise — the
        // interval row reads "Repeats every N months" (ADR-0024).
        assertEquals("Day", intervalUnitLabel(1, IntervalUnit.DAYS).english())
        assertEquals("Days", intervalUnitLabel(2, IntervalUnit.DAYS).english())
        assertEquals("Week", intervalUnitLabel(1, IntervalUnit.WEEKS).english())
        assertEquals("Weeks", intervalUnitLabel(2, IntervalUnit.WEEKS).english())
        assertEquals("Month", intervalUnitLabel(1, IntervalUnit.MONTHS).english())
        assertEquals("Months", intervalUnitLabel(3, IntervalUnit.MONTHS).english())
        assertEquals("Year", intervalUnitLabel(1, IntervalUnit.YEARS).english())
        assertEquals("Years", intervalUnitLabel(10, IntervalUnit.YEARS).english())
        // An unparseable interval reads plural, the web's fallback.
        assertEquals("Months", intervalUnitLabel(null, IntervalUnit.MONTHS).english())
    }

    @Test
    fun `the draft interval is a whole number of at least one`() {
        assertEquals(2, parseIntervalValue("2"))
        assertEquals(1, parseIntervalValue(" 1 "))
        assertNull(parseIntervalValue(""))
        assertNull(parseIntervalValue("abc"))
        assertNull(parseIntervalValue("2.5"))
        // A negative parses; the form's Save validation (at least 1)
        // rejects it with a Field Error.
        assertEquals(-1, parseIntervalValue("-1"))
    }

    @Test
    fun `definitions order by next due date ascending with case-insensitive name ties`() {
        val seeded = listOf(
            cost(1, "Rent", "2026-09-01"),
            cost(2, "Netflix", "2026-08-15"),
            cost(3, "Gym", "2026-08-15"),
            cost(4, "gym", "2026-08-15"),
        )
        val sorted = sortByNextDue(seeded)
        assertEquals(listOf(3, 4, 2, 1), sorted.map { it.id })
    }

    private fun cost(id: Int, name: String, nextDue: String) = RecurringCostDto(
        id = id,
        name = name,
        amount = "10.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = nextDue,
        next_unpaid_occurrence_date = nextDue,
        created_at = "2026-08-01T10:00:00Z",
    )
}
