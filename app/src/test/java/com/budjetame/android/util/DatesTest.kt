package com.budjetame.android.util

import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class DatesTest {

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
    fun `month labels render the abbreviated month and the year`() {
        assertEquals("Aug 2026", Dates.monthLabel("2026-08", Locale.ENGLISH))
    }

    @Test
    fun `compact month labels render the short month and the year`() {
        assertEquals("Aug 2026", Dates.monthLabelCompact("2026-08", Locale.ENGLISH))
    }

    @Test
    fun `short bar labels render the month and January carries the year`() {
        assertEquals("Aug", Dates.shortMonthLabel("2026-08", Locale.ENGLISH))
        assertEquals("Jan '26", Dates.shortMonthLabel("2026-01", Locale.ENGLISH))
    }
    // Locale `it` (issue #59, web #114): rendered dates localize.

    @Test
    fun `under it month labels use Italian month names`() {
        assertEquals("ago 2026", Dates.monthLabel("2026-08", Locale.ITALY))
        assertEquals("ago 2026", Dates.monthLabelCompact("2026-08", Locale.ITALY))
        assertEquals("ago", Dates.shortMonthLabel("2026-08", Locale.ITALY))
        assertEquals("gen '26", Dates.shortMonthLabel("2026-01", Locale.ITALY))
    }

    @Test
    fun `ledger dates stay ISO under en and localize under it`() {
        assertEquals("2026-08-23", Dates.ledgerDate("2026-08-23", Locale.US))
        assertEquals("23 ago 2026", Dates.ledgerDate("2026-08-23", Locale.ITALY))
        assertEquals("1 gen 2027", Dates.ledgerDate("2027-01-01", Locale.ITALY))
    }

    @Test
    fun `month picker names follow the Locale`() {
        assertEquals("Aug", Dates.shortMonthName(8, Locale.US))
        assertEquals("ago", Dates.shortMonthName(8, Locale.ITALY))
    }
}
