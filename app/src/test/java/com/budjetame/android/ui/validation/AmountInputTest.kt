package com.budjetame.android.ui.validation

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Amount Input parser's matrix (ADR-0009, the umbrella's Seam 3): the
 * tolerant parser mirrored from the web's ADR-0029 contract, plus the
 * message mapping a field error reads. This is the exhaustive matrix — the
 * forms' parse-through-the-UI cases live in their own per-form suites.
 */
class AmountInputTest {

    // --- The tolerant matrix (last separator wins) ---

    @Test
    fun `parses a dot-decimal amount`() {
        assertEquals(BigDecimal("17.5"), parseAmount("17.5"))
    }

    @Test
    fun `parses a comma-decimal amount to the same value`() {
        assertEquals(BigDecimal("17.5"), parseAmount("17,5"))
    }

    @Test
    fun `parses a US grouped amount`() {
        assertEquals(BigDecimal("2002.01"), parseAmount("2,002.01"))
    }

    @Test
    fun `parses an Italian grouped amount`() {
        assertEquals(BigDecimal("1000420.45"), parseAmount("1.000.420,45"))
    }

    @Test
    fun `reads a lone dot-grouped amount as an integer, not a decimal`() {
        assertEquals(BigDecimal("1000"), parseAmount("1.000"))
    }

    @Test
    fun `reads a lone comma-grouped amount as an integer, not a decimal`() {
        assertEquals(BigDecimal("2500"), parseAmount("2,500"))
    }

    @Test
    fun `parses a grouped amount with comma decimals`() {
        assertEquals(BigDecimal("1000.00"), parseAmount("1.000,00"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(BigDecimal("17.5"), parseAmount("  17.5  "))
    }

    // --- The matrix's nulls ---

    @Test
    fun `rejects the empty string`() {
        assertNull(parseAmount(""))
    }

    @Test
    fun `rejects a whitespace-only string`() {
        assertNull(parseAmount("   "))
    }

    @Test
    fun `rejects letters`() {
        assertNull(parseAmount("abc"))
    }

    @Test
    fun `rejects a trailing letter`() {
        assertNull(parseAmount("17.5abc"))
    }

    @Test
    fun `rejects an exponent`() {
        assertNull(parseAmount("1e5"))
    }

    @Test
    fun `rejects a whitespace grouping`() {
        assertNull(parseAmount("1 000"))
    }

    @Test
    fun `rejects multiple decimal points`() {
        assertNull(parseAmount("1.2.3"))
    }

    @Test
    fun `rejects a second comma decimal`() {
        assertNull(parseAmount("1,2,3"))
    }

    @Test
    fun `rejects a broken grouping chain`() {
        assertNull(parseAmount("1.5.5"))
    }

    @Test
    fun `rejects an empty integer part`() {
        assertNull(parseAmount(",5"))
    }

    @Test
    fun `rejects zero`() {
        assertNull(parseAmount("0"))
    }

    @Test
    fun `rejects zero with cents`() {
        assertNull(parseAmount("0.00"))
    }

    // --- Signs are never an amount (the character filter, like the web) ---

    @Test
    fun `rejects a negative amount`() {
        assertNull(parseAmount("-5"))
    }

    @Test
    fun `rejects a leading sign`() {
        assertNull(parseAmount("+5"))
    }

    // --- The message mapping (field errors read these, never re-parse) ---

    @Test
    fun `a blank amount says enter an amount`() {
        assertEquals(Messages.AMOUNT_EMPTY, amountErrorMessage(""))
        assertEquals(Messages.AMOUNT_EMPTY, amountErrorMessage("   "))
    }

    @Test
    fun `an unparseable amount says it does not look like an amount`() {
        assertEquals(Messages.AMOUNT_NOT_A_NUMBER, amountErrorMessage("abc"))
        assertEquals(Messages.AMOUNT_NOT_A_NUMBER, amountErrorMessage("17.5abc"))
        assertEquals(Messages.AMOUNT_NOT_A_NUMBER, amountErrorMessage("1.2.3"))
        assertEquals(Messages.AMOUNT_NOT_A_NUMBER, amountErrorMessage("+5"))
    }

    @Test
    fun `a zero or negative amount says it must be positive`() {
        assertEquals(Messages.AMOUNT_POSITIVE, amountErrorMessage("0"))
        assertEquals(Messages.AMOUNT_POSITIVE, amountErrorMessage("0.00"))
        assertEquals(Messages.AMOUNT_POSITIVE, amountErrorMessage("0,00"))
        assertEquals(Messages.AMOUNT_POSITIVE, amountErrorMessage("-5"))
        assertEquals(Messages.AMOUNT_POSITIVE, amountErrorMessage("-17,5"))
    }

    @Test
    fun `a valid amount has no error message`() {
        assertNull(amountErrorMessage("17.5"))
        assertNull(amountErrorMessage("17,5"))
        assertNull(amountErrorMessage("1.000"))
        assertNull(amountErrorMessage("  5.00  "))
    }
}