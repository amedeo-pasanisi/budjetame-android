package com.budjetame.android.ui.common

import com.budjetame.android.R
import java.math.BigDecimal

/*
 * The Amount Input half of the shared validation layer (ADR-0009, mirror of
 * web ADR-0029). Every money field in every entity form (Amount, Opening
 * balance) reads its text through `parseAmountInput` and reports through
 * `amountFieldError` — no form re-implements either.
 */

/** The Amount Field Error messages, defined once and identical to the web's
 * copy, as string resources (issue #71). Forms reach them through
 * [amountFieldError]; they are public so a test can name the message it
 * expects. */
object AmountInputMessages {
    val EMPTY: UiText = UiText.of(R.string.field_error_amount_empty)
    val INVALID: UiText = UiText.of(R.string.field_error_amount_invalid)
    val NOT_POSITIVE: UiText = UiText.of(R.string.field_error_amount_not_positive)
}

private val AMOUNT_CHARACTERS = Regex("^[0-9.,]+$")
private val GROUPED_OR_PLAIN_INTEGER = Regex("""^(\d{1,3}([.,]\d{3})*|\d{4,})$""")
private val SIGNED_SIMPLE_NUMBER = Regex("""^-?\d+([.,]\d+)?$""")

/**
 * Parses a user-typed money amount per the **Amount Input** contract
 * (ADR-0009 / web ADR-0029): the last `.` or `,` is the decimal point and
 * earlier ones are thousands groupings; a lone separator followed by exactly
 * three digits is a thousands grouping. So `17.5` and `17,5` → 17.5,
 * `2,002.01` → 2002.01, `1.000.420,45` → 1000420.45, `1.000` → 1000,
 * `2,500` → 2500, `1.000,00` → 1000.00. Surrounding whitespace is trimmed.
 *
 * Returns a strictly positive [BigDecimal], or null for anything else:
 * empty, letters, signs, exponents, inner spaces, malformed groupings
 * (`1.2.3`, `,5`), zero and negatives.
 *
 * Only what is *accepted* is tolerant: display, storage and the wire stay
 * US-canonical. Send the parsed value as `parseAmountInput(raw)!!.toPlainString()`
 * (e.g. `1.000.420,45` → `"1000420.45"`), never the raw text the user typed.
 */
fun parseAmountInput(raw: String): BigDecimal? {
    val value = raw.trim()
    if (!AMOUNT_CHARACTERS.matches(value)) return null

    val separatorCount = value.count { it == '.' || it == ',' }
    if (separatorCount == 0) return positiveOrNull(value)

    val lastSeparator = maxOf(value.lastIndexOf('.'), value.lastIndexOf(','))
    val integerPart = value.substring(0, lastSeparator)
    val fractionalPart = value.substring(lastSeparator + 1)

    // A lone separator with exactly three digits after it is a thousands
    // grouping (1.000 → 1000), never a three-decimal fraction: no realistic
    // money amount has three decimals.
    if (separatorCount == 1 && fractionalPart.length == 3) {
        return positiveOrNull(integerPart + fractionalPart)
    }

    // Otherwise the last separator is the decimal point; every earlier one
    // must group exactly three digits, or the integer part is a plain
    // ungrouped number (2100,00 → 2100).
    if (!GROUPED_OR_PLAIN_INTEGER.matches(integerPart)) return null
    val digits = integerPart.replace(".", "").replace(",", "")
    return positiveOrNull(if (fractionalPart.isEmpty()) digits else "$digits.$fractionalPart")
}

private fun positiveOrNull(canonical: String): BigDecimal? =
    canonical.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }

/**
 * The Field Error for a mandatory money field, or null when [raw] is a valid
 * Amount Input: [AmountInputMessages.EMPTY] when blank,
 * [AmountInputMessages.NOT_POSITIVE] for a plain number that is zero or
 * negative (`0`, `0,00`, `-5`), [AmountInputMessages.INVALID] for anything
 * else that does not parse.
 *
 * An optional money field (a new Wallet's Opening balance, where blank means
 * €0) calls it only when the text is not blank.
 */
fun amountFieldError(raw: String): UiText? {
    val value = raw.trim()
    return when {
        value.isEmpty() -> AmountInputMessages.EMPTY
        parseAmountInput(value) != null -> null
        SIGNED_SIMPLE_NUMBER.matches(value) -> AmountInputMessages.NOT_POSITIVE
        else -> AmountInputMessages.INVALID
    }
}
