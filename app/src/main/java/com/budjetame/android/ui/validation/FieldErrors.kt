package com.budjetame.android.ui.validation

/**
 * The shared validation layer (ADR-0009, mirroring the web's ADR-0029): the
 * tolerant Amount Input parser, the Field Errors model, the message
 * contract, and the inline render convention. Every entity form consumes
 * this module instead of re-implementing any of it — the per-form
 * validators (the Transaction, Recurring Cost/Income, Category, Wallet, and
 * Import row tickets) all build on the pieces here.
 */

/**
 * The message for one invalid form field, keyed by the field's key, so a
 * form can mail the right message to the right field. A form's `validate()`
 * returns one of these on a failed Save attempt; the field renders it
 * inline beneath itself (see [FieldErrorText]). Server rejections
 * (duplicate names, merge collisions) are not Field Errors — they keep the
 * existing form-level error banner, and the two error kinds never mix.
 */
typealias FieldErrors = Map<String, String>

/** Build a Field Errors set from field-key → message pairs — the Kotlin
 * mirror of the web's `Record<string, string>` literal:
 * `fieldErrors(FieldKey.AMOUNT to Messages.AMOUNT_EMPTY)`. */
fun fieldErrors(vararg entries: Pair<String, String>): FieldErrors = mapOf(*entries)

/**
 * The field keys the entity forms' validators and renderers share, named
 * after the draft field they report on: the Transaction form's amount, the
 * Category form's name, a Transfer's From/To legs, a Recurring form's
 * interval and Start date, an Import row's date. One key per field, so the
 * validator that sets an error and the field that renders it can never
 * drift.
 */
object FieldKey {
    const val AMOUNT = "amount"
    const val NAME = "name"
    const val WALLET = "wallet"
    const val SOURCE_WALLET = "sourceWallet"
    const val DESTINATION_WALLET = "destinationWallet"
    const val INTERVAL = "interval"
    const val START_DATE = "startDate"
    const val DATE = "date"
    const val OPENING_BALANCE = "openingBalance"
}

/**
 * The user-visible Field Error messages (ADR-0009, identical to the web):
 * the umbrella's message contract, named so the per-form validators consume
 * them instead of re-declaring (or drifting from) them.
 */
object Messages {
    /** Any Amount field left blank. */
    const val AMOUNT_EMPTY = "Enter an amount"

    /** An Amount the grammar rejects: letters, signs, malformed groupings. */
    const val AMOUNT_NOT_A_NUMBER =
        "That doesn't look like an amount — use digits and one . or , for decimals"

    /** An Amount that parses to zero. */
    const val AMOUNT_POSITIVE = "Amount must be a positive number"

    /** The Name field of the Category, Wallet, and Recurring forms. */
    const val NAME_EMPTY = "Enter a name"

    /** A Recurring interval set to 0, empty, or not a whole number. */
    const val INTERVAL_MIN = "The interval must be at least 1"

    /** A Recurring Start date cleared while editing — at creation an empty
     * date means "start today" and stays allowed (ADR-0024). */
    const val START_DATE_REQUIRED = "Choose a start date"

    /** An Import row with no date. */
    const val DATE_REQUIRED = "Choose a date"

    /** A Transfer missing a leg. */
    const val TRANSFER_SOURCE_REQUIRED = "Choose the source wallet."
    const val TRANSFER_DESTINATION_REQUIRED = "Choose the destination wallet."

    /** An Expense or Income with no Wallet. */
    const val WALLET_REQUIRED = "Choose a wallet."

    /** A Transfer whose two legs name the same Wallet. */
    const val TRANSFER_DISTINCT = "Source and destination must be different wallets."

    /** An Income left on a Contact Wallet — enforced at Save, never by
     * silently swapping the selection (ADR-0017). */
    const val INCOME_NOT_ON_CONTACT = "Incomes can't be recorded on contact wallets."
}

/**
 * The per-form validation contract (ADR-0009): every entity form's
 * `validate()` is a pure function from the modal's draft to its Field
 * Errors — one entry per wrong field, all errors at once — called only from
 * the submit path. A failed validation stores the errors on the modal state
 * (a field-key → message map, kept distinct from the existing server
 * `error` string) and returns without writing; the errors render under
 * their fields and persist while typing, refreshing only on the next Save
 * attempt. A valid draft returns an empty set and the submit proceeds
 * exactly as before. Validation never gates the Save button — Save is
 * disabled only for in-flight work (submitting/deleting/locating), never
 * because of input.
 */