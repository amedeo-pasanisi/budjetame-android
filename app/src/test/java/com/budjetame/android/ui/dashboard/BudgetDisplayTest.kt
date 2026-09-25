package com.budjetame.android.ui.dashboard

import com.budjetame.android.data.api.BudgetDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Budget card's pure text rules (web issues #65, #100), ported from
 * the web app's BudgetCard: the big number's floor, the frame line
 * "€Y this month (€A income − €B costs)" (Monthly Spendable, its recurring
 * breakdown), the daily allowance line "€X per day", the bucket's
 * transient "€X over today's budget" note, and the Remaining Monthly
 * Spendable bottom line — "€X left this month", or the month's red
 * "€X over this month's budget" when the whole frame is blown, including
 * the negative-supersede rule: never two over-notes at once. These are the
 * cheap spots where porting bugs hide (spec #13 testing decisions), so they
 * get direct JVM tests.
 */
class BudgetDisplayTest {

    private fun budget(
        spendableToday: String = "49.80",
        remaining: String = "333.40",
        monthlySpendable: String = "500.00",
        incomesTotal: String = "2100.00",
        costsTotal: String = "850.00",
        dailyAllowance: String = "16.60",
    ) = BudgetDto(
        month = "2026-08",
        monthly_spendable = monthlySpendable,
        recurring_incomes_total = incomesTotal,
        recurring_costs_total = costsTotal,
        daily_allowance = dailyAllowance,
        spendable_today = spendableToday,
        remaining_monthly_spendable = remaining,
    )

    @Test
    fun `the normal card shows the frame line with recurring breakdown and the amount left this month`() {
        val text = budgetCardText(budget())

        assertEquals("49.80", text.spendableToday)
        assertNull(text.bucketNote)
        assertEquals(
            "€500.00 this month (€2,100.00 income − €850.00 costs)",
            text.frameLine,
        )
        assertEquals("€16.60 per day", text.dailyLine)
        assertEquals("€333.40 left this month", text.remainingLine)
        assertFalse(text.remainingOver)
    }

    @Test
    fun `a negative bucket floors the big number and adds the over-note`() {
        val text = budgetCardText(budget(spendableToday = "-12.34", remaining = "300.00"))

        assertEquals("0.00", text.spendableToday)
        assertEquals("€12.34 over today's budget", text.bucketNote)
        assertEquals("€300.00 left this month", text.remainingLine)
        assertFalse(text.remainingOver)
    }

    @Test
    fun `a negative month supersedes the bucket note with the month's bottom line`() {
        val text = budgetCardText(budget(spendableToday = "-433.40", remaining = "-100.00"))

        assertEquals("0.00", text.spendableToday)
        assertNull(text.bucketNote)
        assertEquals("€100.00 over this month's budget", text.remainingLine)
        assertTrue(text.remainingOver)
    }

    @Test
    fun `a negative month with a positive bucket still blows the frame`() {
        val text = budgetCardText(budget(spendableToday = "2.00", remaining = "-100.00"))

        // Never two over-notes at once — and this bucket has none to give.
        assertEquals("2.00", text.spendableToday)
        assertNull(text.bucketNote)
        assertEquals("€100.00 over this month's budget", text.remainingLine)
        assertTrue(text.remainingOver)
    }
}
