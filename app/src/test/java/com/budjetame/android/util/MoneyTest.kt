package com.budjetame.android.util

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
}
