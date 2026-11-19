package com.budjetame.android.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Display helpers for amounts. The API sends amounts as strings ("12.50");
 * the formatting mirrors the web app's `format.ts`.
 *
 * Locale-aware (issue #59, web #114): under the Locale `it` amounts render in
 * Italian notation, the euro sign after a no-break space ("1.000,42 €", the
 * web's `Intl.NumberFormat('it-IT')`); under `en` — any other [Locale] —
 * they render exactly as the API sends them ("€1000.42"). Wire formats,
 * stored amounts, Export and Backup never go through here.
 */
object Money {

    /** Display an amount string from the API as euros ("€100.00",
     * "€-15.00"; under `it` "100,00 €", "-15,00 €"). */
    fun formatEuros(amount: String, locale: Locale = Locale.US): String {
        if (!locale.isItalian()) return "€$amount"
        val value = amount.toBigDecimalOrNull() ?: return "€$amount"
        return italian(value)
    }

    /**
     * Display a Wallet balance with a sign, in the transaction-amount
     * convention (web issue #47): "+€50.00" for a positive balance, "-€30.00"
     * for a negative one, and unsigned "€0.00" for zero — a settled Contact
     * is neutral, like a Transfer. A positive Credit Card balance means the
     * bank owes the user. Under `it` the same signs lead the Italian form
     * ("+50,00 €").
     */
    fun formatSignedEuros(amount: String, locale: Locale = Locale.US): String {
        if (amount.startsWith("-")) {
            return "-${formatEuros(amount.drop(1), locale)}"
        }
        return if (BigDecimal(amount) > BigDecimal.ZERO) {
            "+${formatEuros(amount, locale)}"
        } else {
            formatEuros(amount, locale)
        }
    }

    private fun italian(value: BigDecimal): String {
        // A fresh format per call: DecimalFormat is not thread-safe.
        val format = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ITALY)).apply {
            roundingMode = RoundingMode.HALF_UP
        }
        return "${format.format(value)} €"
    }
}

/** True for the display Locale of the Account Locale `it`. */
internal fun Locale.isItalian(): Boolean = language == "it"
