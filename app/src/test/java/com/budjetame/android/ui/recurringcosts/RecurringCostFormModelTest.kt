package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the Recurring Costs form/screen logic (ticket #22):
 * the interval text, the due-date override's shape per unit, the ordering,
 * and the form gates — ported from the web app's recurringCosts.ts and
 * RecurringCostForm.tsx. */
class RecurringCostFormModelTest {

    @Test
    fun `interval text is singular for one and plural otherwise`() {
        assertEquals("Every day", intervalText(1, IntervalUnit.DAYS))
        assertEquals("Every 5 days", intervalText(5, IntervalUnit.DAYS))
        assertEquals("Every week", intervalText(1, IntervalUnit.WEEKS))
        assertEquals("Every 2 weeks", intervalText(2, IntervalUnit.WEEKS))
        assertEquals("Every month", intervalText(1, IntervalUnit.MONTHS))
        assertEquals("Every 3 months", intervalText(3, IntervalUnit.MONTHS))
        assertEquals("Every year", intervalText(1, IntervalUnit.YEARS))
        assertEquals("Every 10 years", intervalText(10, IntervalUnit.YEARS))
    }

    @Test
    fun `interval unit labels match the web's option order`() {
        assertEquals(
            listOf(IntervalUnit.DAYS, IntervalUnit.WEEKS, IntervalUnit.MONTHS, IntervalUnit.YEARS),
            INTERVAL_UNIT_OPTIONS,
        )
        assertEquals("Days", intervalUnitLabel(IntervalUnit.DAYS))
        assertEquals("Weeks", intervalUnitLabel(IntervalUnit.WEEKS))
        assertEquals("Months", intervalUnitLabel(IntervalUnit.MONTHS))
        assertEquals("Years", intervalUnitLabel(IntervalUnit.YEARS))
    }

    @Test
    fun `the draft interval is a whole number of at least one`() {
        assertEquals(2, parseIntervalValue("2"))
        assertEquals(1, parseIntervalValue(" 1 "))
        assertNull(parseIntervalValue(""))
        assertNull(parseIntervalValue("abc"))
        assertNull(parseIntervalValue("2.5"))
        // A negative parses; the canSubmit gate (at least 1) rejects it.
        assertEquals(-1, parseIntervalValue("-1"))
    }

    @Test
    fun `month intervals carry a day-of-month override only`() {
        assertEquals(15 to null, dueOverrideFor(IntervalUnit.MONTHS, 15, 7))
        assertEquals(null to null, dueOverrideFor(IntervalUnit.MONTHS, null, 7))
        // A stale month pick from a year interval is dropped, never sent.
        assertEquals(null to null, dueOverrideFor(IntervalUnit.MONTHS, null, null))
    }

    @Test
    fun `year intervals carry a month plus day pair or nothing`() {
        assertEquals(15 to 7, dueOverrideFor(IntervalUnit.YEARS, 15, 7))
        assertEquals(null to null, dueOverrideFor(IntervalUnit.YEARS, 15, null))
        assertEquals(null to null, dueOverrideFor(IntervalUnit.YEARS, null, 7))
        assertEquals(null to null, dueOverrideFor(IntervalUnit.YEARS, null, null))
    }

    @Test
    fun `day and week intervals never carry an override`() {
        assertEquals(null to null, dueOverrideFor(IntervalUnit.DAYS, 15, 7))
        assertEquals(null to null, dueOverrideFor(IntervalUnit.WEEKS, 15, 7))
    }

    @Test
    fun `a half-picked year pair is incomplete and gates the save`() {
        assertTrue(yearOverrideIncomplete(15, null))
        assertTrue(yearOverrideIncomplete(null, 7))
        assertFalse(yearOverrideIncomplete(15, 7))
        assertFalse(yearOverrideIncomplete(null, null))
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
        next_due_date = nextDue,
        next_unpaid_occurrence_date = nextDue,
        created_at = "2026-08-01T10:00:00Z",
    )
}
