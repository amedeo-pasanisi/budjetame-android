package com.budjetame.android.util

import java.math.BigDecimal

/**
 * Display helpers for amounts. The API sends amounts as strings ("12.50");
 * the formatting mirrors the web app's `format.ts` exactly.
 */
object Money {

    /** Display an amount string from the API as euros ("€100.00", "€-15.00"). */
    fun formatEuros(amount: String): String = "€$amount"

    /**
     * Display a Wallet balance with a sign, in the transaction-amount
     * convention (web issue #47): "+€50.00" for a positive balance, "-€30.00"
     * for a negative one, and unsigned "€0.00" for zero — a settled Contact
     * is neutral, like a Transfer. A positive Credit Card balance means the
     * bank owes the user.
     */
    fun formatSignedEuros(amount: String): String {
        if (amount.startsWith("-")) {
            return "-€${amount.drop(1)}"
        }
        return if (BigDecimal(amount) > BigDecimal.ZERO) "+€$amount" else "€$amount"
    }
}
