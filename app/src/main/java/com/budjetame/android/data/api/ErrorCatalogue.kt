package com.budjetame.android.data.api

import com.budjetame.android.R

/**
 * A simple pure-function error catalogue (mirroring the web's
 * client‑side approach — Q23 in the web grilling): maps known server
 * error message prefixes/substrings to string resource keys.
 * Unknown/unmapped messages fall back to the raw API text (English).
 *
 * Every mapping is a (substring → resource id) pair. The match is
 * case‑insensitive prefix‑based — the first matching entry wins.
 */
object ErrorCatalogue {

    private data class Mapping(val prefix: String, val resId: Int)

    /**
     * Ordered list of known error substrings, most specific first.
     * Each entry's [prefix] is matched as a case-insensitive prefix of
     * the server's `detail` field.
     */
    private val MAPPINGS: List<Mapping> = listOf(
        // --- Wallet scoping errors ---
        Mapping("unknown wallet", R.string.error_unknown_wallet),
        Mapping("wallet not found", R.string.error_wallet_not_found),
        Mapping("wallet is frozen", R.string.error_wallet_frozen),
        Mapping("balance is not zero", R.string.error_wallet_balance_not_zero),

        // --- Category scoping errors ---
        Mapping("unknown category", R.string.error_unknown_category),
        Mapping("category not found", R.string.error_category_not_found),

        // --- Recurring scoping errors ---
        Mapping("unknown recurring cost", R.string.error_unknown_recurring_cost),
        Mapping("unknown recurring income", R.string.error_unknown_recurring_income),
        Mapping("recurring definition not found", R.string.error_recurring_not_found),
        Mapping("recurring cost not found", R.string.error_recurring_cost_not_found),
        Mapping("recurring income not found", R.string.error_recurring_income_not_found),

        // --- Transaction rule errors ---
        Mapping("cannot record income on contact wallet", R.string.error_income_on_contact),
        Mapping("cannot record expense on checking", R.string.error_expense_on_checking),
        Mapping("cannot have same source and destination", R.string.error_same_wallet),
        Mapping("wallet must be different", R.string.error_wallet_must_differ),
        Mapping("negative cash wallet not allowed", R.string.error_cash_negative),

        // --- Duplicate / conflict errors ---
        Mapping("name already taken", R.string.error_name_taken),
        Mapping("already exists", R.string.error_already_exists),

        // --- Undo errors ---
        Mapping("undo not available", R.string.error_undo_not_available),
        Mapping("already undone", R.string.error_already_undone),
        Mapping("occurrence already paid", R.string.error_occurrence_already_paid),
        Mapping("cannot restore pin", R.string.error_cannot_restore_pin),

        // --- Occurrence errors ---
        Mapping("occurrence not found", R.string.error_occurrence_not_found),

        // --- General validation ---
        Mapping("validation failed", R.string.error_validation_failed),
        Mapping("cannot be empty", R.string.error_field_empty),
    )

    /**
     * Map the server's error [detail] to a string resource id, or null
     * when no known mapping matches. When null is returned the caller
     * should fall back to the raw English [detail] text.
     */
    fun resIdFor(detail: String?): Int? {
        if (detail == null) return null
        val lower = detail.lowercase()
        return MAPPINGS.firstOrNull { lower.startsWith(it.prefix) }?.resId
    }

    /**
     * Convenience: returns the resource id when known, otherwise null.
     * Same as [resIdFor] — kept for symmetry with the web's errorMap.
     */
    fun map(detail: String?): Int? = resIdFor(detail)

    /**
     * True when the catalogue knows how to map the given [detail].
     */
    fun knows(detail: String?): Boolean = resIdFor(detail) != null
}