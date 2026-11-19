package com.budjetame.android.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `formatEuros prefixes the euro sign`() {
        assertEquals("€100.00", Money.formatEuros("100.00"))
        assertEquals("€-15.00", Money.formatEuros("-15.00"))
        assertEquals("€0.00", Money.formatEuros("0.00"))
    }

    @Test
    fun `formatSignedEuros signs positive balances`() {
        assertEquals("+€50.00", Money.formatSignedEuros("50.00"))
    }

    @Test
    fun `formatSignedEuros signs negative balances`() {
        assertEquals("-€30.00", Money.formatSignedEuros("-30.00"))
    }

    @Test
    fun `formatSignedEuros leaves zero unsigned`() {
        assertEquals("€0.00", Money.formatSignedEuros("0.00"))
    }

    @Test
    fun `a negative zero keeps its sign`() {
        // Mirrors format.ts: the startsWith('-') check wins before the
        // numeric comparison.
        assertEquals("-€0.00", Money.formatSignedEuros("-0.00"))
    }

    @Test
    fun `tiny positive amounts are signed`() {
        assertEquals("+€0.01", Money.formatSignedEuros("0.01"))
    }
    // Locale `it` (issue #59, web #114): Italian notation, the euro sign
    // after the amount behind a no-break space — Intl.NumberFormat('it-IT').

    @Test
    fun `under it amounts use Italian notation with the euro sign after`() {
        assertEquals("1.000,42 €", Money.formatEuros("1000.42", Locale.ITALY))
        assertEquals("-1.000,42 €", Money.formatEuros("-1000.42", Locale.ITALY))
        assertEquals("0,00 €", Money.formatEuros("0.00", Locale.ITALY))
        assertEquals("1.000.420,45 €", Money.formatEuros("1000420.45", Locale.ITALY))
        assertEquals("15,50 €", Money.formatEuros("15.5", Locale.ITALY))
    }

    @Test
    fun `under it signed balances keep the sign rules`() {
        assertEquals("+1.000,42 €", Money.formatSignedEuros("1000.42", Locale.ITALY))
        assertEquals("-1.000,42 €", Money.formatSignedEuros("-1000.42", Locale.ITALY))
        assertEquals("0,00 €", Money.formatSignedEuros("0.00", Locale.ITALY))
    }

    @Test
    fun `under en amounts stay exactly as the API sends them`() {
        assertEquals("€1000.42", Money.formatEuros("1000.42", Locale.US))
        assertEquals("€-1000.42", Money.formatEuros("-1000.42", Locale.US))
        assertEquals("+€1000.42", Money.formatSignedEuros("1000.42", Locale.US))
        assertEquals("-€0.00", Money.formatSignedEuros("-0.00", Locale.US))
    }
}
