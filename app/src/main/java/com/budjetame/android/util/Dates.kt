package com.budjetame.android.util

import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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
     * An API month string ("2026-08") as its short form in the display
     * [locale]: "Aug 2026" under `en`, "ago 2026" under `it` (issue #59,
     * the web's `formatMonth`).
     */
    fun monthLabel(isoMonth: String, locale: Locale = Locale.US): String =
        YearMonth.parse(isoMonth).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

    /**
     * An API month string ("2026-08") in the compact field form: "Aug 2026"
     * under `en`, "ago 2026" under `it`.
     */
    fun monthLabelCompact(isoMonth: String, locale: Locale = Locale.US): String =
        YearMonth.parse(isoMonth).format(DateTimeFormatter.ofPattern("MMM yyyy", locale))

    /**
     * "2026-08" → "Aug", a trend bar's label; a January bar also carries
     * the year ("Jan '26") so long ranges stay readable — the web app's
     * `formatShortMonth`. Under `it`: "ago", "gen '26".
     */
    fun shortMonthLabel(isoMonth: String, locale: Locale = Locale.US): String {
        val month = YearMonth.parse(isoMonth)
        val short = month.format(DateTimeFormatter.ofPattern("MMM", locale))
        return if (month.monthValue == 1) "$short '${month.year.toString().takeLast(2)}" else short
    }

    /** A month (1–12) as the month picker's short name: "Aug" under `en`,
     * "ago" under `it`. */
    fun shortMonthName(monthValue: Int, locale: Locale = Locale.US): String =
        Month.of(monthValue).getDisplayName(TextStyle.SHORT, locale)

    /**
     * A ledger day "2026-08-23" as the user sees it — the web's
     * `formatLedgerDate`: the ISO form itself under `en`, localized
     * ("23 ago 2026") under `it`. The stored and wire form stays ISO; a
     * value that is not an ISO day is shown as it is.
     */
    fun ledgerDate(isoDay: String, locale: Locale = Locale.US): String {
        if (!locale.isItalian()) return isoDay
        val day = runCatching { LocalDate.parse(isoDay) }.getOrNull() ?: return isoDay
        return day.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}
