package com.budjetame.android.ui.recurringcosts

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.SkipAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure JVM tests for the Recurring Costs form/screen logic (ticket #22):
 * the interval text, the interval-unit select labels (singular when N is 1),
 * the ordering, the skip-button label (ADR-0016), and the form gates —
 * ported from the web app's recurringCosts.ts and RecurringCostForm.tsx.
 * The due-date override is gone (ADR-0024): no helper shapes one anymore. */
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
    fun `interval unit select labels match the web's option order and read singular for one`() {
        assertEquals(
            listOf(IntervalUnit.DAYS, IntervalUnit.WEEKS, IntervalUnit.MONTHS, IntervalUnit.YEARS),
            INTERVAL_UNIT_OPTIONS,
        )
        // Next to a 1 the unit reads singular, plural otherwise — the
        // interval row reads "Repeats every N months" (ADR-0024).
        assertEquals("Day", intervalUnitLabel(1, IntervalUnit.DAYS))
        assertEquals("Days", intervalUnitLabel(2, IntervalUnit.DAYS))
        assertEquals("Week", intervalUnitLabel(1, IntervalUnit.WEEKS))
        assertEquals("Weeks", intervalUnitLabel(2, IntervalUnit.WEEKS))
        assertEquals("Month", intervalUnitLabel(1, IntervalUnit.MONTHS))
        assertEquals("Months", intervalUnitLabel(3, IntervalUnit.MONTHS))
        assertEquals("Year", intervalUnitLabel(1, IntervalUnit.YEARS))
        assertEquals("Years", intervalUnitLabel(10, IntervalUnit.YEARS))
        // An unparseable interval reads plural, the web's fallback.
        assertEquals("Months", intervalUnitLabel(null, IntervalUnit.MONTHS))
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

    @Test
    fun `the skip button label follows the definition's skip action`() {
        // The web's ternary: next_skip_action === 'unskip' ? 'Un-skip' :
        // 'Skip' (ADR-0016) — "Un-skip" exactly when the press would
        // restore a Skipped Occurrence.
        assertEquals("Skip", skipToggleLabel(SkipAction.SKIP))
        assertEquals("Un-skip", skipToggleLabel(SkipAction.UNSKIP))
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
