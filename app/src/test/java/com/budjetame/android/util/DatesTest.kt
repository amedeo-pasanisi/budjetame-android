package com.budjetame.android.util

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DatesTest {

    @Before
    fun setUp() {
        AppLocale.current = Locale.US
    }

    @After
    fun tearDown() {
        AppLocale.current = Locale.US
    }

    @Test
    fun `the single fixed timezone is Europe-Rome`() {
        assertEquals("Europe/Rome", Dates.rome.id)
    }

    @Test
    fun `today in Rome is the Rome calendar day`() {
        assertEquals(LocalDate.now(ZoneId.of("Europe/Rome")), Dates.todayInRome())
    }

    @Test
    fun `api days round-trip through parse and format`() {
        val day = LocalDate.of(2026, 8, 31)
        assertEquals(day, Dates.parseApiDay(Dates.toApiDay(day)))
    }

    @Test
    fun `api days keep the YYYY-MM-DD shape`() {
        assertEquals("2026-08-31", Dates.toApiDay(LocalDate.of(2026, 8, 31)))
    }

    @Test
    fun `monthLabel is wired to AppLocale US`() {
        assertEquals("Aug 2026", Dates.monthLabel("2026-08"))
    }

    @Test
    fun `monthLabel is wired to AppLocale IT`() {
        AppLocale.current = Locale.ITALY
        assertEquals("ago 2026", Dates.monthLabel("2026-08"))
    }

    @Test
    fun `monthLabelCompact is wired to AppLocale US`() {
        assertEquals("Aug 2026", Dates.monthLabelCompact("2026-08"))
    }

    @Test
    fun `monthLabelCompact is wired to AppLocale IT`() {
        AppLocale.current = Locale.ITALY
        assertEquals("ago 2026", Dates.monthLabelCompact("2026-08"))
    }

    @Test
    fun `shortMonthLabel is wired to AppLocale US`() {
        assertEquals("Aug", Dates.shortMonthLabel("2026-08"))
        assertEquals("Jan '26", Dates.shortMonthLabel("2026-01"))
    }

    @Test
    fun `shortMonthLabel is wired to AppLocale IT`() {
        AppLocale.current = Locale.ITALY
        assertEquals("ago", Dates.shortMonthLabel("2026-08"))
        assertEquals("gen '26", Dates.shortMonthLabel("2026-01"))
    }
}