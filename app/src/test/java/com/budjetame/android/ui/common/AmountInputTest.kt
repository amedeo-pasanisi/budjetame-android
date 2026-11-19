package com.budjetame.android.ui.common

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shared validation layer's pure half (ADR-0009, mirror of web ADR-0029,
 * ticket #50): the tolerant Amount Input matrix and the Amount Field Error
 * messages. The forms' submit-and-reveal behavior lives in their own suites.
 */
class AmountInputTest {

    /** Values compare numerically: the matrix names 17.50, the parser may
     * keep the typed scale (17.5) — both are the same Amount. */
    private fun assertParses(expected: String, typed: String) {
        val parsed = parseAmountInput(typed)
        requireNotNull(parsed) { "expected '$typed' to parse to $expected, got null" }
        assertEquals("'$typed'", 0, BigDecimal(expected).compareTo(parsed))
    }

    // --- The ADR-0029 matrix ---

    @Test
    fun `a dot decimal parses`() = assertParses("17.50", "17.5")

    @Test
    fun `a comma decimal parses to the same value`() = assertParses("17.50", "17,5")

    @Test
    fun `a US grouped amount parses`() = assertParses("2002.01", "2,002.01")

    @Test
    fun `an Italian grouped amount parses`() = assertParses("1000420.45", "1.000.420,45")

    @Test
    fun `a lone dot with three digits is a thousands grouping`() = assertParses("1000", "1.000")

    @Test
    fun `a lone comma with three digits is a thousands grouping`() = assertParses("2500", "2,500")

    @Test
    fun `a grouped amount with comma decimals parses`() = assertParses("1000.00", "1.000,00")

    @Test
    fun `an ungrouped four-digit amount parses with either decimal separator`() {
        assertParses("2100", "2100.00")
        assertParses("2100", "2100,00")
        assertParses("12000", "12000")
    }

    @Test
    fun `surrounding whitespace is trimmed`() = assertParses("17.5", "  17,5  ")

    @Test
    fun `anything that is not a positive amount is null`() {
        listOf(
            "", "   ", "abc", "17.5abc", "1e5", "1 000",
            "1.2.3", "1,2,3", "1.5.5", ",5",
            "0", "0.00", "-5", "+5",
        ).forEach { typed -> assertNull("'$typed'", parseAmountInput(typed)) }
    }

    @Test
    fun `the parsed amount goes on the wire in US-canonical form`() {
        assertEquals("1000420.45", parseAmountInput("1.000.420,45")?.toPlainString())
        assertEquals("17.5", parseAmountInput("17,5")?.toPlainString())
    }

    // --- The Amount Field Error ---

    @Test
    fun `an empty amount asks for one`() {
        assertEquals("Enter an amount", amountFieldError(""))
        assertEquals("Enter an amount", amountFieldError("   "))
    }

    @Test
    fun `text that is not a number explains the Amount Input`() {
        val message = "That doesn't look like an amount — use digits and one . or , for decimals"
        assertEquals(message, amountFieldError("abc"))
        assertEquals(message, amountFieldError("1.2.3"))
    }

    @Test
    fun `a zero or negative number must be positive`() {
        val message = "Amount must be a positive number"
        assertEquals(message, amountFieldError("0"))
        assertEquals(message, amountFieldError("0,00"))
        assertEquals(message, amountFieldError("-5"))
    }

    @Test
    fun `a valid amount has no Field Error`() {
        assertNull(amountFieldError("17,5"))
        assertNull(amountFieldError("1.000.420,45"))
    }
}
