package com.budjetame.android.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Date handling. Transaction dates travel as Europe/Rome calendar days
 * ("YYYY-MM-DD"); the backend stores UTC timestamps and buckets reporting
 * months in Europe/Rome — the app's single fixed timezone (CONTEXT.md).
 */
object Dates {

    val rome: ZoneId = ZoneId.of("Europe/Rome")

    /** Today as a calendar day in Europe/Rome. */
    fun todayInRome(): LocalDate = LocalDate.now(rome)

    /** The current month in Europe/Rome (dashboard/reporting buckets). */
    fun currentMonthInRome(): YearMonth = YearMonth.now(rome)

    /** An API day string ("YYYY-MM-DD") for a calendar day. */
    fun toApiDay(date: LocalDate): String = date.toString()

    /** Parse an API day string ("YYYY-MM-DD") into a calendar day. */
    fun parseApiDay(value: String): LocalDate = LocalDate.parse(value)
}
