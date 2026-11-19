package com.budjetame.android.ui.common

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.budjetame.android.R
import com.budjetame.android.data.api.ApiException

/*
 * The client-side error catalogue (issue #71, mirroring web #118): the API
 * speaks English, and a server message the user may read — a Transaction
 * rule, a Wallet freeze refusal, a duplicate name, an Undo whose pin was
 * taken, a Draft approval rule, a Restore or Import validation error — maps
 * here to a string resource, so it renders in the Account's Locale.
 *
 * Each entry's English resource is the backend's `detail`, character for
 * character (backend/app routers and services), so under `en` the text
 * shows exactly as the server wrote it. A message matches whole: exactly,
 * or as a fixed pattern around the values it names (a Wallet name, a
 * count). Text the catalogue does not know passes through unchanged as
 * [UiText.Raw] — still the server's English, never lost.
 */

/** The server message [detail] in the Account's Locale: its catalogue
 * resource, or [detail] itself when the catalogue does not know it. An
 * Import row's parse errors arrive joined by "; " and map part by part. */
fun serverMessage(detail: String): UiText {
    single(detail)?.let { return it }
    val parts = detail.split(JOIN_SEPARATOR)
    if (parts.size > 1) {
        val mapped = parts.map { single(it) }
        if (mapped.any { it != null }) {
            return parts.zip(mapped) { part, text -> text ?: UiText.Raw(part) }
                .reduce { joined, next -> UiText.of(R.string.errors_joined, joined, next) }
        }
    }
    return UiText.Raw(detail)
}

/** This failure's message for the user: the server's `detail` through the
 * catalogue when it sent one, else [fallback]. */
fun Throwable.serverMessageOr(fallback: UiText): UiText =
    (this as? ApiException)?.detail?.let(::serverMessage) ?: fallback

/**
 * The message for an API failure by status (ported from the web
 * transport's `apiErrorMessage`): the status codes are part of the API
 * contract — 409 a duplicate name ([conflictMessage]), 422 a validation
 * refusal — and anything else is [fallback]. A 422 whose [detail] the
 * catalogue knows (a Transaction rule, a frozen Wallet, …) names that rule
 * in the Account's Locale; any other 422 keeps the generic "Check the
 * fields" line, as before.
 */
fun apiErrorMessage(
    status: Int?,
    conflictMessage: UiText,
    fallback: UiText,
    detail: String? = null,
): UiText = when (status) {
    409 -> conflictMessage
    422 -> detail?.let(::serverMessage)?.takeIf { it !is UiText.Raw } ?: UiText.of(R.string.errors_check_fields)
    else -> fallback
}

/** [apiErrorMessage] for [error]: its status, and its `detail` for a 422. */
fun apiErrorMessage(error: ApiException, conflictMessage: UiText, fallback: UiText): UiText =
    apiErrorMessage(error.status, conflictMessage, fallback, error.detail)

private const val JOIN_SEPARATOR = "; "

private fun single(detail: String): UiText? {
    EXACT[detail]?.let { return UiText.of(it) }
    for (pattern in PATTERNS) {
        val match = pattern.regex.matchEntire(detail) ?: continue
        return pattern.text(match.groupValues.drop(1))
    }
    return null
}

/** A message around the values it names: [regex] captures them in the
 * resource's argument order. */
private class Pattern(val regex: Regex, val text: (List<String>) -> UiText)

private fun pattern(regex: String, @StringRes id: Int) =
    Pattern(Regex(regex)) { values -> UiText.Res(id, values) }

/** A counted message: the first capture is the count. */
private fun counted(regex: String, @PluralsRes id: Int) =
    Pattern(Regex(regex)) { values -> UiText.Plural(id, values.first().toInt()) }

private val EXACT: Map<String, Int> = mapOf(
    // Transaction rules (services/transactions.py)
    "This Occurrence was already paid by another Transaction" to R.string.errors_server_occurrence_already_paid,
    "Frozen Wallets are read-only" to R.string.errors_server_frozen_wallets_read_only,
    "Transfers need source and destination Wallets" to R.string.errors_server_transfer_needs_wallets,
    "A Transfer may carry a Recurring Cost link only when its destination is a Contact Wallet" to
        R.string.errors_server_transfer_cost_link,
    "A Transfer may carry a Recurring Income link only when its source is a Contact Wallet" to
        R.string.errors_server_transfer_income_link,
    "Transfers between two Contact Wallets never carry a recurring link" to
        R.string.errors_server_transfer_contacts_no_link,
    "A Category attaches only to Transactions of its Type" to R.string.errors_server_category_type,
    "Type must be expense, income, or transfer" to R.string.errors_server_type_invalid,
    "wallet_id is required for Expense and Income" to R.string.errors_server_wallet_required,
    "source and destination Wallets are only for Transfers" to R.string.errors_server_legs_only_transfers,
    "Incomes can't be recorded on Contact Wallets" to R.string.errors_server_income_on_contact,
    "Opening Balance Transactions are read-only" to R.string.errors_server_opening_balance_read_only,
    "Transaction id already exists" to R.string.errors_server_transaction_id_exists,
    "Transfers use source and destination Wallets and never carry a Category" to
        R.string.errors_server_transfer_shape,
    "Source and Destination must be different Wallets" to R.string.errors_server_same_wallet,
    "Only Expenses can be linked to a Recurring Cost" to R.string.errors_server_only_expenses_cost,
    "Only Incomes can be linked to a Recurring Income" to R.string.errors_server_only_incomes_income,
    "Transfers never carry a Category" to R.string.errors_server_transfer_no_category,
    "latitude and longitude must be set together" to R.string.errors_server_location_pair,
    // Draft approval rules (services/draft_transactions.py)
    "A type is required: expense, income, or transfer" to R.string.errors_server_draft_type_required,
    "An amount is required" to R.string.errors_server_draft_amount_required,
    "A date is required" to R.string.errors_server_draft_date_required,
    // Wallets (wallets.py, services/wallets.py)
    "Only a Wallet with balance exactly €0.00 can be frozen" to R.string.errors_server_wallet_not_freezable,
    "Only a frozen wallet can be deleted — freeze it first" to R.string.errors_server_wallet_delete_frozen_only,
    "A Wallet with this name already exists" to R.string.errors_server_wallet_name_taken,
    "This wallet already exists" to R.string.errors_server_wallet_undo_exists,
    "Undo failed: a Wallet with this name now exists" to R.string.errors_server_wallet_undo_name_taken,
    "Contact Wallets start at €0: money moves only via Transfers" to R.string.errors_server_contact_opening_balance,
    // Categories
    "A Category with this name already exists" to R.string.errors_server_category_name_taken,
    "A merge target must be another Category of the same Type" to R.string.errors_server_category_merge_type,
    // Recurring definitions (recurring.py, services/recurring.py, services/occurrences.py)
    "A Recurring Cost with this name already exists" to R.string.errors_server_recurring_cost_name_taken,
    "A Recurring Income with this name already exists" to R.string.errors_server_recurring_income_name_taken,
    "This Recurring Cost already exists" to R.string.errors_server_recurring_cost_undo_exists,
    "This Recurring Income already exists" to R.string.errors_server_recurring_income_undo_exists,
    "Undo failed: a Recurring Cost with this name now exists" to R.string.errors_server_recurring_cost_undo_name_taken,
    "Undo failed: a Recurring Income with this name now exists" to
        R.string.errors_server_recurring_income_undo_name_taken,
    "A frozen recurring cost cannot be edited. Unfreeze it first." to R.string.errors_server_recurring_cost_frozen_edit,
    "A frozen recurring income cannot be edited. Unfreeze it first." to
        R.string.errors_server_recurring_income_frozen_edit,
    "Only a frozen recurring cost can be deleted — freeze it first" to
        R.string.errors_server_recurring_cost_delete_frozen_only,
    "Only a frozen recurring income can be deleted — freeze it first" to
        R.string.errors_server_recurring_income_delete_frozen_only,
    "A recurring cost always carries a start date — it can be changed, never unset." to
        R.string.errors_server_recurring_cost_start_date,
    "A recurring income always carries a start date — it can be changed, never unset." to
        R.string.errors_server_recurring_income_start_date,
    "A frozen recurring definition has no Occurrences to skip. Unfreeze it first." to
        R.string.errors_server_occurrence_frozen,
    "This date is not one of the definition's Occurrences." to R.string.errors_server_occurrence_not_a_date,
    "A paid occurrence can never be skipped." to R.string.errors_server_occurrence_paid_skip,
    // Not found
    "Wallet not found" to R.string.errors_server_wallet_not_found,
    "Category not found" to R.string.errors_server_category_not_found,
    "Transaction not found" to R.string.errors_server_transaction_not_found,
    "Recurring Cost not found" to R.string.errors_server_recurring_cost_not_found,
    "Recurring Income not found" to R.string.errors_server_recurring_income_not_found,
    "Draft transaction not found" to R.string.errors_server_draft_not_found,
    "Wallet, Category, Recurring Cost, or Recurring Income not found" to R.string.errors_server_reference_not_found,
    // Auth (auth.py)
    "An Account with this email already exists" to R.string.errors_server_account_exists,
    "Incorrect email or password" to R.string.errors_server_incorrect_credentials,
    "Invalid Google sign-in" to R.string.errors_server_google_invalid,
    "This reset link is invalid or has expired" to R.string.errors_server_reset_link_invalid,
    // Import (imports.py, services/imports.py)
    "The file is empty" to R.string.errors_server_import_file_empty,
    "Only .csv and .xlsx files are supported" to R.string.errors_server_import_unsupported,
    "source and destination wallets are only for Transfers" to R.string.errors_server_import_legs_only_transfers,
    "Transfer rows use source wallet and destination wallet, not wallet" to R.string.errors_server_import_transfer_legs,
    "Transfers never carry a category" to R.string.errors_server_import_transfer_no_category,
    "Date is required" to R.string.errors_server_import_date_required,
    "Amount is required" to R.string.errors_server_import_amount_required,
    "Description is too long (max 500 characters)" to R.string.errors_server_import_description_too_long,
    // Restore (services/backup.py)
    "Restore failed: the backup data could not be applied" to R.string.errors_server_restore_failed,
    "Metadata sheet is empty" to R.string.errors_server_restore_metadata_empty,
    "Wallets sheet has a row with empty name" to R.string.errors_server_restore_wallet_empty_name,
    "Categories sheet has row with empty name or type" to R.string.errors_server_restore_category_empty,
    "Recurring Costs sheet has row with empty name" to R.string.errors_server_restore_recurring_cost_empty_name,
    "Recurring Incomes sheet has row with empty name" to R.string.errors_server_restore_recurring_income_empty_name,
    // Assistant and Assistant Settings
    "The Assistant Key cannot be empty" to R.string.errors_server_assistant_key_empty,
    "The provider rejected the Assistant Key: check the key in Settings" to R.string.errors_server_assistant_key_rejected,
    "The provider's model list is unavailable right now: type a model id or try again later" to
        R.string.errors_server_assistant_models_unavailable,
    "Assistant Settings are not configured" to R.string.errors_server_assistant_not_configured,
    "No Assistant Settings: choose a provider first" to R.string.errors_server_assistant_no_settings,
)

private val PATTERNS: List<Pattern> = listOf(
    // Wallets and Recurring definitions
    counted("""This wallet has (\d+) transactions?""", R.plurals.errors_server_wallet_has_transactions),
    counted(
        """(\d+) transactions? (?:is|are) linked to this recurring cost — unlink (?:it|them) first""",
        R.plurals.errors_server_recurring_cost_linked,
    ),
    counted(
        """(\d+) transactions? (?:is|are) linked to this recurring income — unlink (?:it|them) first""",
        R.plurals.errors_server_recurring_income_linked,
    ),
    pattern(
        """A Recurring Cost named "(.*)" already exists — rename it first\.""",
        R.string.errors_server_recurring_cost_named_exists,
    ),
    pattern(
        """A Recurring Income named "(.*)" already exists — rename it first\.""",
        R.string.errors_server_recurring_income_named_exists,
    ),
    // Import
    pattern("""Missing required column\(s\): (.+)""", R.string.errors_server_import_missing_columns),
    pattern("""Unknown target row\(s\): (.+)""", R.string.errors_server_import_unknown_rows),
    pattern("""('.*') is not a calendar day \(YYYY-MM-DD\)\.""", R.string.errors_server_occurrence_bad_date),
    pattern("""Unknown wallet '(.*)'""", R.string.errors_server_import_unknown_wallet),
    pattern("""Unknown expense category '(.*)'""", R.string.errors_server_import_unknown_expense_category),
    pattern("""Unknown income category '(.*)'""", R.string.errors_server_import_unknown_income_category),
    pattern("""Wallet '(.*)' is frozen""", R.string.errors_server_import_wallet_frozen),
    pattern(
        """Category '(.*)' is a expense category, not income""",
        R.string.errors_server_import_category_expense_not_income,
    ),
    pattern(
        """Category '(.*)' is a income category, not expense""",
        R.string.errors_server_import_category_income_not_expense,
    ),
    pattern("""Row (\S+) duplicates an existing transaction""", R.string.errors_server_import_row_duplicate),
    pattern("""Type must be expense, income, or transfer \(got '(.*)'\)""", R.string.errors_server_import_type_got),
    pattern("""Invalid date '(.*)' \(use YYYY-MM-DD\)""", R.string.errors_server_import_date_invalid),
    pattern("""Invalid amount '(.*)'""", R.string.errors_server_import_amount_invalid),
    pattern("""Amount must be positive \(got '(.*)'\)""", R.string.errors_server_import_amount_not_positive),
    pattern("""Amount is too large \(got '(.*)'\)""", R.string.errors_server_import_amount_too_large),
    pattern("""Invalid location '(.*)' \(use 'lat,lon'\)""", R.string.errors_server_import_location_invalid),
    // Restore
    pattern("""Could not parse workbook: (.*)""", R.string.errors_server_restore_unparseable),
    pattern("""Missing sheets: (.+)""", R.string.errors_server_restore_missing_sheets),
    pattern(
        """(Wallets|Categories|Recurring Costs|Recurring Incomes|Skips|Transactions) sheet is empty \(no header\)""",
        R.string.errors_server_restore_sheet_empty,
    ),
    pattern("""Sheet (.+) has (\d+) columns, expected (\d+)""", R.string.errors_server_restore_sheet_columns),
    pattern("""Sheet (.+) column (\d+): expected (.+), got (.+)""", R.string.errors_server_restore_sheet_column),
    pattern("""Duplicate wallet name in backup: (.+)""", R.string.errors_server_restore_duplicate_wallet),
    pattern(
        """Duplicate category in backup: (.+) \(expense\)""",
        R.string.errors_server_restore_duplicate_expense_category,
    ),
    pattern(
        """Duplicate category in backup: (.+) \(income\)""",
        R.string.errors_server_restore_duplicate_income_category,
    ),
    pattern(
        """Duplicate recurring cost name in backup: (.+)""",
        R.string.errors_server_restore_duplicate_recurring_cost,
    ),
    pattern(
        """Duplicate recurring income name in backup: (.+)""",
        R.string.errors_server_restore_duplicate_recurring_income,
    ),
    pattern("""Invalid decimal value: (.+)""", R.string.errors_server_restore_invalid_decimal),
    pattern("""Invalid boolean value: (.+)""", R.string.errors_server_restore_invalid_boolean),
    pattern("""Invalid location in Transactions: (.+)""", R.string.errors_server_restore_invalid_location),
    pattern(
        """Backup was exported from (.+), not from (.+)\. Proceeding will replace all data with the backup's contents\.""",
        R.string.errors_server_restore_origin_mismatch,
    ),
    // Assistant and Assistant Settings
    pattern(
        """(.+) lists its models with a key: enter the Assistant Key first""",
        R.string.errors_server_assistant_models_key_required,
    ),
    pattern("""No adapter for the provider (.+) yet""", R.string.errors_server_assistant_no_adapter),
    pattern("""Assistant provider error: (.*)""", R.string.errors_server_assistant_provider_error),
)
