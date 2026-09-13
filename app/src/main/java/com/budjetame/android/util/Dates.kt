package com.budjetame.android.util

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    /**
     * An API month string ("2026-08") as its short English form
     * ("Aug 2026") — always uses abbreviated English month names.
     * i18n will come in the future, so English is the default.
     */
    fun monthLabel(isoMonth: String): String = monthLabel(isoMonth, Locale.US)

    internal fun monthLabel(isoMonth: String, locale: Locale): String =
        YearMonth.parse(isoMonth).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

    /**
     * An API month string ("2026-08") in the compact field form
     * ("Aug 2026") — always abbreviated English. i18n will come later.
     */
    fun monthLabelCompact(isoMonth: String): String = monthLabelCompact(isoMonth, Locale.US)

    internal fun monthLabelCompact(isoMonth: String, locale: Locale): String =
        YearMonth.parse(isoMonth).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

    /**
     * "2026-08" → "Aug", a trend bar's label; a January bar also carries
     * the year ("Jan '26") so long ranges stay readable — the web app's
     * `shortMonthLabel`, always abbreviated English. i18n will come later.
     */
    fun shortMonthLabel(isoMonth: String): String = shortMonthLabel(isoMonth, Locale.US)

    internal fun shortMonthLabel(isoMonth: String, locale: Locale): String {
        val month = YearMonth.parse(isoMonth)
        val short = month.format(DateTimeFormatter.ofPattern("MMM", locale))
        return if (month.monthValue == 1) "$short '${month.year.toString().takeLast(2)}" else short
    }
}
