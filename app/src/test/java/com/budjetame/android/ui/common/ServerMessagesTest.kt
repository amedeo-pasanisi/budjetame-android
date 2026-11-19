package com.budjetame.android.ui.common

import com.budjetame.android.R
import com.budjetame.android.data.api.ApiException
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The client-side error catalogue (issue #71, mirroring web #118): a known
 * server `detail` maps to its string resource — so it renders in the
 * Account's Locale — and its English resource is the server's text,
 * character for character, so under `en` nothing changes. Unknown text
 * passes through untouched.
 */
class ServerMessagesTest {

    /** Each sample `detail`, as the backend writes it, with the resource it maps to. */
    private val known: List<Pair<String, Int>> = listOf(
        // Transaction rules
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
        // Draft approval rules
        "A type is required: expense, income, or transfer" to R.string.errors_server_draft_type_required,
        "An amount is required" to R.string.errors_server_draft_amount_required,
        "A date is required" to R.string.errors_server_draft_date_required,
        // Wallets
        "Only a Wallet with balance exactly €0.00 can be frozen" to R.string.errors_server_wallet_not_freezable,
        "Only a frozen wallet can be deleted — freeze it first" to R.string.errors_server_wallet_delete_frozen_only,
        "A Wallet with this name already exists" to R.string.errors_server_wallet_name_taken,
        "This wallet already exists" to R.string.errors_server_wallet_undo_exists,
        "Undo failed: a Wallet with this name now exists" to R.string.errors_server_wallet_undo_name_taken,
        "Contact Wallets start at €0: money moves only via Transfers" to
            R.string.errors_server_contact_opening_balance,
        // Categories
        "A Category with this name already exists" to R.string.errors_server_category_name_taken,
        "A merge target must be another Category of the same Type" to R.string.errors_server_category_merge_type,
        // Recurring
        "A Recurring Cost with this name already exists" to R.string.errors_server_recurring_cost_name_taken,
        "A Recurring Income with this name already exists" to R.string.errors_server_recurring_income_name_taken,
        "This Recurring Cost already exists" to R.string.errors_server_recurring_cost_undo_exists,
        "This Recurring Income already exists" to R.string.errors_server_recurring_income_undo_exists,
        "Undo failed: a Recurring Cost with this name now exists" to
            R.string.errors_server_recurring_cost_undo_name_taken,
        "Undo failed: a Recurring Income with this name now exists" to
            R.string.errors_server_recurring_income_undo_name_taken,
        "A Recurring Cost named \"Rent\" already exists — rename it first." to
            R.string.errors_server_recurring_cost_named_exists,
        "A Recurring Income named \"Salary\" already exists — rename it first." to
            R.string.errors_server_recurring_income_named_exists,
        "A frozen recurring cost cannot be edited. Unfreeze it first." to
            R.string.errors_server_recurring_cost_frozen_edit,
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
        "Wallet, Category, Recurring Cost, or Recurring Income not found" to
            R.string.errors_server_reference_not_found,
        // Auth
        "An Account with this email already exists" to R.string.errors_server_account_exists,
        "Incorrect email or password" to R.string.errors_server_incorrect_credentials,
        "Invalid Google sign-in" to R.string.errors_server_google_invalid,
        "This reset link is invalid or has expired" to R.string.errors_server_reset_link_invalid,
        // Import
        "The file is empty" to R.string.errors_server_import_file_empty,
        "Unknown target row(s): 4, 7" to R.string.errors_server_import_unknown_rows,
        "'2026-02-30' is not a calendar day (YYYY-MM-DD)." to R.string.errors_server_occurrence_bad_date,
        "Only .csv and .xlsx files are supported" to R.string.errors_server_import_unsupported,
        "Missing required column(s): amount, date" to R.string.errors_server_import_missing_columns,
        "Unknown wallet 'Cash'" to R.string.errors_server_import_unknown_wallet,
        "Unknown expense category 'Food'" to R.string.errors_server_import_unknown_expense_category,
        "Unknown income category 'Salary'" to R.string.errors_server_import_unknown_income_category,
        "Wallet 'Old card' is frozen" to R.string.errors_server_import_wallet_frozen,
        "Category 'Food' is a expense category, not income" to
            R.string.errors_server_import_category_expense_not_income,
        "Category 'Salary' is a income category, not expense" to
            R.string.errors_server_import_category_income_not_expense,
        "source and destination wallets are only for Transfers" to
            R.string.errors_server_import_legs_only_transfers,
        "Transfer rows use source wallet and destination wallet, not wallet" to
            R.string.errors_server_import_transfer_legs,
        "Transfers never carry a category" to R.string.errors_server_import_transfer_no_category,
        "Row 12 duplicates an existing transaction" to R.string.errors_server_import_row_duplicate,
        "Type must be expense, income, or transfer (got 'spesa')" to R.string.errors_server_import_type_got,
        "Date is required" to R.string.errors_server_import_date_required,
        "Invalid date '31/01/2026' (use YYYY-MM-DD)" to R.string.errors_server_import_date_invalid,
        "Amount is required" to R.string.errors_server_import_amount_required,
        "Invalid amount 'abc'" to R.string.errors_server_import_amount_invalid,
        "Amount must be positive (got '-5')" to R.string.errors_server_import_amount_not_positive,
        "Amount is too large (got '1e20')" to R.string.errors_server_import_amount_too_large,
        "Description is too long (max 500 characters)" to R.string.errors_server_import_description_too_long,
        "Invalid location 'here' (use 'lat,lon')" to R.string.errors_server_import_location_invalid,
        // Restore
        "Restore failed: the backup data could not be applied" to R.string.errors_server_restore_failed,
        "Could not parse workbook: File is not a zip file" to R.string.errors_server_restore_unparseable,
        "Missing sheets: Skips, Wallets" to R.string.errors_server_restore_missing_sheets,
        "Metadata sheet is empty" to R.string.errors_server_restore_metadata_empty,
        "Recurring Costs sheet is empty (no header)" to R.string.errors_server_restore_sheet_empty,
        "Sheet 'Wallets' has 3 columns, expected 5" to R.string.errors_server_restore_sheet_columns,
        "Sheet 'Wallets' column 2: expected 'type', got 'kind'" to R.string.errors_server_restore_sheet_column,
        "Wallets sheet has a row with empty name" to R.string.errors_server_restore_wallet_empty_name,
        "Categories sheet has row with empty name or type" to R.string.errors_server_restore_category_empty,
        "Recurring Costs sheet has row with empty name" to R.string.errors_server_restore_recurring_cost_empty_name,
        "Recurring Incomes sheet has row with empty name" to
            R.string.errors_server_restore_recurring_income_empty_name,
        "Duplicate wallet name in backup: 'Cash'" to R.string.errors_server_restore_duplicate_wallet,
        "Duplicate category in backup: 'Food' (expense)" to R.string.errors_server_restore_duplicate_expense_category,
        "Duplicate category in backup: 'Gifts' (income)" to R.string.errors_server_restore_duplicate_income_category,
        "Duplicate recurring cost name in backup: 'Rent'" to R.string.errors_server_restore_duplicate_recurring_cost,
        "Duplicate recurring income name in backup: 'Salary'" to
            R.string.errors_server_restore_duplicate_recurring_income,
        "Invalid decimal value: 'abc'" to R.string.errors_server_restore_invalid_decimal,
        "Invalid boolean value: 'maybe'" to R.string.errors_server_restore_invalid_boolean,
        "Invalid location in Transactions: '1;2'" to R.string.errors_server_restore_invalid_location,
        "Backup was exported from a@example.com, not from b@example.com. " +
            "Proceeding will replace all data with the backup's contents." to
            R.string.errors_server_restore_origin_mismatch,
        // Assistant and Assistant Settings
        "The Assistant Key cannot be empty" to R.string.errors_server_assistant_key_empty,
        "The provider rejected the Assistant Key: check the key in Settings" to
            R.string.errors_server_assistant_key_rejected,
        "The provider's model list is unavailable right now: type a model id or try again later" to
            R.string.errors_server_assistant_models_unavailable,
        "Assistant Settings are not configured" to R.string.errors_server_assistant_not_configured,
        "No Assistant Settings: choose a provider first" to R.string.errors_server_assistant_no_settings,
        "OpenRouter lists its models with a key: enter the Assistant Key first" to
            R.string.errors_server_assistant_models_key_required,
        "No adapter for the provider Google Gemini yet" to R.string.errors_server_assistant_no_adapter,
        "Assistant provider error: could not reach OpenRouter" to R.string.errors_server_assistant_provider_error,
    )

    @Test
    fun `each known message maps to its resource`() {
        for ((detail, id) in known) {
            val text = serverMessage(detail)
            assertTrue("$detail → $text", text is UiText.Res && text.id == id)
        }
    }

    @Test
    fun `under en each known message reads exactly as the server wrote it`() {
        for ((detail, _) in known) {
            assertEquals(detail, serverMessage(detail).english())
        }
    }

    @Test
    fun `a parameterised message carries its values`() {
        assertEquals(
            UiText.of(R.string.errors_server_import_unknown_wallet, "Cash"),
            serverMessage("Unknown wallet 'Cash'"),
        )
        assertEquals(
            UiText.of(R.string.errors_server_restore_origin_mismatch, "a@example.com", "b@example.com"),
            serverMessage(
                "Backup was exported from a@example.com, not from b@example.com. " +
                    "Proceeding will replace all data with the backup's contents.",
            ),
        )
    }

    @Test
    fun `a counted message is a plural`() {
        val one = serverMessage("This wallet has 1 transaction")
        assertEquals(UiText.Plural(R.plurals.errors_server_wallet_has_transactions, 1), one)
        assertEquals("This wallet has 1 transaction", one.english())
        assertEquals("This wallet has 3 transactions", serverMessage("This wallet has 3 transactions").english())

        val linkedOne = "1 transaction is linked to this recurring cost — unlink it first"
        assertEquals(UiText.Plural(R.plurals.errors_server_recurring_cost_linked, 1), serverMessage(linkedOne))
        assertEquals(linkedOne, serverMessage(linkedOne).english())
        val linkedMany = "4 transactions are linked to this recurring income — unlink them first"
        assertEquals(UiText.Plural(R.plurals.errors_server_recurring_income_linked, 4), serverMessage(linkedMany))
        assertEquals(linkedMany, serverMessage(linkedMany).english())
    }

    @Test
    fun `unknown text passes through unchanged`() {
        assertEquals(UiText.Raw("Something new went wrong"), serverMessage("Something new went wrong"))
        // Neither a prefix nor a suffix of a known message is a match.
        assertEquals(UiText.Raw("Wallet not found!"), serverMessage("Wallet not found!"))
        assertEquals(UiText.Raw("The Wallet not found"), serverMessage("The Wallet not found"))
    }

    @Test
    fun `an import row's joined parse errors map part by part`() {
        val joined = "Date is required; Invalid amount 'abc'"
        val text = serverMessage(joined)
        assertEquals(
            UiText.of(
                R.string.errors_joined,
                UiText.of(R.string.errors_server_import_date_required),
                UiText.of(R.string.errors_server_import_amount_invalid, "abc"),
            ),
            text,
        )
        assertEquals(joined, text.english())
        // A joined text with no known part passes through whole.
        assertEquals(UiText.Raw("Foo; Bar"), serverMessage("Foo; Bar"))
    }

    @Test
    fun `an API failure reads its detail through the catalogue, else the fallback`() {
        val fallback = UiText.of(R.string.undo_error)
        val withDetail = ApiException(409, "Transaction id already exists", message = "x")
        assertEquals(UiText.of(R.string.errors_server_transaction_id_exists), withDetail.serverMessageOr(fallback))
        assertEquals(fallback, ApiException(500, null, message = "x").serverMessageOr(fallback))
        assertEquals(fallback, IllegalStateException("offline").serverMessageOr(fallback))
    }

    @Test
    fun `apiErrorMessage maps the contract statuses`() {
        val conflict = UiText.of(R.string.errors_wallets_conflict)
        val fallback = UiText.of(R.string.errors_wallets_create)
        assertEquals(conflict, apiErrorMessage(409, conflict, fallback))
        assertEquals("Check the fields and try again.", apiErrorMessage(422, conflict, fallback).english())
        assertEquals(fallback, apiErrorMessage(500, conflict, fallback))
        assertEquals(fallback, apiErrorMessage(null, conflict, fallback))
    }

    @Test
    fun `a 422 whose detail the catalogue knows names the rule, any other keeps the generic line`() {
        val conflict = UiText.of(R.string.errors_transactions_conflict)
        val fallback = UiText.of(R.string.errors_transactions_save)
        val rule = ApiException(422, "Frozen Wallets are read-only", message = "x")
        assertEquals(UiText.of(R.string.errors_server_frozen_wallets_read_only), apiErrorMessage(rule, conflict, fallback))
        val unknown = ApiException(422, "Some new rule", message = "x")
        assertEquals("Check the fields and try again.", apiErrorMessage(unknown, conflict, fallback).english())
        // A 409 keeps the client's own conflict copy whatever the detail says.
        val taken = ApiException(409, "A Wallet with this name already exists", message = "x")
        assertEquals(conflict, apiErrorMessage(taken, conflict, fallback))
    }

    @Test
    fun `every translatable message of this ticket has an Italian translation`() {
        val files = listOf(
            "strings_errors.xml",
            "strings_field_errors.xml",
            "strings_undo.xml",
            "strings_assistant.xml",
        )
        for (file in files) {
            val english = names(resFile("values", file), translatableOnly = true)
            val italian = names(resFile("values-it", file), translatableOnly = false)
            assertEquals("$file: keys missing in values-it", emptySet<String>(), english - italian)
            assertEquals("$file: keys only in values-it", emptySet<String>(), italian - english)
        }
    }

    private fun resFile(dir: String, name: String): File =
        listOf("src/main/res/$dir/$name", "app/src/main/res/$dir/$name").map(::File).first { it.isFile }

    private fun names(file: File, translatableOnly: Boolean): Set<String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return listOf("string", "plurals").flatMap { tag ->
            val nodes = root.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.filter { !translatableOnly || it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
            .toSet()
    }
}
