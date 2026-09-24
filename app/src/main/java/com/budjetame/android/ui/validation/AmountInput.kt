package com.budjetame.android.ui.validation

import java.math.BigDecimal

/**
 * Parse a user-typed money amount per the Amount Input contract (ADR-0009,
 * mirroring the web's ADR-0029): the last `.` or `,` is the decimal point,
 * earlier ones are thousands groupings, and a lone separator followed by
 * exactly three digits is a thousands grouping. So `17.5`, `17,5`,
 * `2,002.01`, `1.000.420,45`, `1.000` and `1.000,00` all parse; `1.2.3`,
 * `1,2,3`, `1.5.5`, `,5` and `1 000` do not. Surrounding whitespace is
 * trimmed; a positive BigDecimal is returned, or null for anything invalid
 * (empty, letters, signs, exponents, malformed groupings, non-positive
 * values). Display, storage, and export stay US-canonical — the parser only
 * changes what is accepted.
 */
fun parseAmount(raw: String): BigDecimal? =
    amountValue(raw)?.takeIf { it > BigDecimal.ZERO }

/**
 * The tolerant parse before the positivity gate — the amount the user typed
 * as a number, or null when the text is not an amount at all (letters,
 * signs, exponents, spaces, malformed groupings). Zero and negative values
 * DO parse here (a `-5` never reaches the parser — the character filter
 * rejects signs — but `0` and `0.00` do), so [amountErrorMessage] can tell
 * "that doesn't look like an amount" apart from "must be positive" without
 * re-implementing the grammar.
 */
internal fun amountValue(raw: String): BigDecimal? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    // Digits and the two separators only — letters, signs, exponents,
    // spaces are never an amount.
    if (!value.all { it in '0'..'9' || it == '.' || it == ',' }) return null

    val separators = value.count { it == '.' || it == ',' }
    if (separators == 0) return BigDecimal(value)

    val lastSeparator = maxOf(value.lastIndexOf('.'), value.lastIndexOf(','))
    val integerPart = value.substring(0, lastSeparator)
    val fractionalPart = value.substring(lastSeparator + 1)

    // A lone separator with exactly three digits after it is a thousands
    // grouping (1.000 → 1000, 2,500 → 2500), never a three-decimal
    // fraction: no realistic money amount has three decimals.
    if (separators == 1 && fractionalPart.length == 3) {
        return BigDecimal(integerPart + fractionalPart)
    }

    // Otherwise the last separator is the decimal point and every earlier
    // separator is a thousands grouping, which must sit between groups of
    // exactly three digits (2,002.01 → 2002.01, 1.000.420,45 → 1000420.45).
    // A malformed integer part — 1.2.3, or an empty integer part before a
    // decimal comma — is not an amount.
    if (!INTEGER_PART.matches(integerPart)) return null

    return BigDecimal("${integerPart.filter(Char::isDigit)}.$fractionalPart")
}

/** An integer part with valid thousands groupings: 1-3 leading digits, then
 * zero or more groups of exactly three digits, each introduced by either
 * separator (`.000` and `,420` may mix, like the web's digit+separator
 * chain). Also accepts a plain digit sequence of any length — a 4+-digit
 * integer typed without separators (e.g. `2500.00`) must parse as-is,
 * not be rejected because the thousands notation expects groups of three. */
private val INTEGER_PART = Regex("\\d{1,3}([.,]\\d{3})*|\\d+")

/**
 * The Field Error message for a typed Amount, or null when it is a valid
 * positive amount — the Amount field's message contract (ADR-0009, the
 * umbrella's user-visible strings): "Enter an amount" for a blank field,
 * the "doesn't look like an amount" line for text the grammar rejects,
 * "Amount must be a positive number" for a value that parses to zero.
 */
fun amountErrorMessage(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return Messages.AMOUNT_EMPTY
    // A leading minus names a negative amount — not a formatting mistake:
    // it gets the positivity message, never the "doesn't look like an
    // amount" line (the umbrella: Amount ≤ 0 → "must be a positive
    // number"). The parser itself never yields negatives — the sign is not
    // in its grammar, like the web's character filter — so the message
    // layer reads past the minus sign.
    if (trimmed.startsWith("-") && amountValue(trimmed.removePrefix("-")) != null) {
        return Messages.AMOUNT_POSITIVE
    }
    val value = amountValue(trimmed) ?: return Messages.AMOUNT_NOT_A_NUMBER
    return if (value <= BigDecimal.ZERO) Messages.AMOUNT_POSITIVE else null
}