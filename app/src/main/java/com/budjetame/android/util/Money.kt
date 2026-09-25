package com.budjetame.android.util

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency

/**
 * Display helpers for amounts. The API sends amounts as strings ("12.50");
 * the formatting mirrors the web app's `format.ts` exactly, but uses
 * [java.text.NumberFormat] with the app-wide [AppLocale.current] so that
 * Italian users see `1.000,42 €` and English users see `€1,000.42`.
 */
object Money {

    /** The shared currency formatter — recreated on locale changes. */
    private fun formatter(): NumberFormat {
        val nf = NumberFormat.getCurrencyInstance(AppLocale.current)
        nf.currency = Currency.getInstance("EUR")
        return nf
    }

    /** Display an amount string from the API as euros ("€100.00", "-€15.00"). */
    fun formatEuros(amount: String): String = formatter().format(BigDecimal(amount))

    /**
     * Display a Wallet balance with a sign, in the transaction-amount
     * convention (web issue #47): "+€50.00" for a positive balance, "-€30.00"
     * for a negative one, and unsigned "€0.00" for zero — a settled Contact
     * is neutral, like a Transfer. A positive Credit Card balance means the
     * bank owes the user.
     */
    fun formatSignedEuros(amount: String): String {
        if (amount.startsWith("-")) {
            return "-${formatter().format(BigDecimal(amount.drop(1)))}"
        }
        return if (BigDecimal(amount) > BigDecimal.ZERO) {
            "+${formatter().format(BigDecimal(amount))}"
        } else {
            formatter().format(BigDecimal(amount))
        }
    }
}