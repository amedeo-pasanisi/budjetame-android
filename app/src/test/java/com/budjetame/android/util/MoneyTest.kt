package com.budjetame.android.util

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MoneyTest {

    @Before
    fun setUp() {
        AppLocale.current = Locale.US
    }

    @After
    fun tearDown() {
        AppLocale.current = Locale.US
    }

    @Test
    fun `formatEuros renders US English notation`() {
        assertEquals("€100.00", Money.formatEuros("100.00"))
        assertEquals("-€15.00", Money.formatEuros("-15.00"))
        assertEquals("€0.00", Money.formatEuros("0.00"))
    }

    @Test
    fun `formatEuros renders Italian notation with locale it`() {
        AppLocale.current = Locale.ITALY
        assertEquals("100,00\u00a0€", Money.formatEuros("100.00"))
        assertEquals("-15,00\u00a0€", Money.formatEuros("-15.00"))
        assertEquals("0,00\u00a0€", Money.formatEuros("0.00"))
    }

    @Test
    fun `formatSignedEuros signs positive balances in US English`() {
        assertEquals("+€50.00", Money.formatSignedEuros("50.00"))
        assertEquals("+€0.01", Money.formatSignedEuros("0.01"))
    }

    @Test
    fun `formatSignedEuros signs positive balances in Italian`() {
        AppLocale.current = Locale.ITALY
        assertEquals("+50,00\u00a0€", Money.formatSignedEuros("50.00"))
        assertEquals("+0,01\u00a0€", Money.formatSignedEuros("0.01"))
    }

    @Test
    fun `formatSignedEuros signs negative balances in US English`() {
        assertEquals("-€30.00", Money.formatSignedEuros("-30.00"))
    }

    @Test
    fun `formatSignedEuros signs negative balances in Italian`() {
        AppLocale.current = Locale.ITALY
        assertEquals("-30,00\u00a0€", Money.formatSignedEuros("-30.00"))
    }

    @Test
    fun `formatSignedEuros leaves zero unsigned in US English`() {
        assertEquals("€0.00", Money.formatSignedEuros("0.00"))
    }

    @Test
    fun `formatSignedEuros leaves zero unsigned in Italian`() {
        AppLocale.current = Locale.ITALY
        assertEquals("0,00\u00a0€", Money.formatSignedEuros("0.00"))
    }

    @Test
    fun `a negative zero keeps its sign in US English`() {
        assertEquals("-€0.00", Money.formatSignedEuros("-0.00"))
    }

    @Test
    fun `a negative zero keeps its sign in Italian`() {
        AppLocale.current = Locale.ITALY
        assertEquals("-0,00\u00a0€", Money.formatSignedEuros("-0.00"))
    }
}