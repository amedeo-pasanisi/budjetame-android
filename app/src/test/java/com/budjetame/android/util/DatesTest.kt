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
    fun `month labels render the long month and the year`() {
        assertEquals("August 2026", Dates.monthLabel("2026-08", Locale.ENGLISH))
    }
}
