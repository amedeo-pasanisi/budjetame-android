package com.budjetame.android.ui.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Transaction form's fiddly validation rules (spec #13 testing
 * decisions: Compose UI tests for form validation): the mandatory-fields
 * gate on Save, the Transfer's distinct-Wallet rule and its absent Category
 * field, and the Contact-Wallet eligibility split (ADR-0017) — offered to an
 * Expense, never to an Income. The modal is driven with a local state
 * harness; the type-change resets themselves live in the ViewModel and are
 * covered by the seam tests.
 */
@RunWith(AndroidJUnit4::class)
class TransactionFormTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val cash = WalletDto(1, "Cash", WalletType.CASH, "100.00", false, "2026-08-01T10:00:00Z")
    private val card = WalletDto(2, "Card", WalletType.CREDIT_CARD, "0.00", false, "2026-08-01T10:00:00Z")
    private val marco = WalletDto(3, "Marco", WalletType.CONTACT, "0.00", false, "2026-08-01T10:00:00Z")
    private val food = CategoryDto(1, "Food", CategoryType.EXPENSE, "🍕", "#ef4444", "2026-08-01T10:00:00Z")
    private val rent = RecurringCostDto(
        id = 1,
        name = "Rent",
        amount = "800.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = "2026-09-01",
        next_unpaid_occurrence_date = "2026-08-01",
        created_at = "2026-08-01T10:00:00Z",
    )
    private val salary = RecurringIncomeDto(
        id = 1,
        name = "Salary",
        amount = "2500.00",
        interval_value = 1,
        interval_unit = IntervalUnit.MONTHS,
        // Every definition always carries a start date (ADR-0024).
        start_date = "2026-08-01",
        next_due_date = "2026-09-01",
        next_unpaid_occurrence_date = "2026-08-01",
        created_at = "2026-08-01T10:00:00Z",
    )

    private fun setForm(
        initial: TransactionsViewModel.ModalState,
        wallets: List<WalletDto>,
        categories: List<CategoryDto>,
        recurringCosts: List<RecurringCostDto> = emptyList(),
        recurringIncomes: List<RecurringIncomeDto> = emptyList(),
        onAddWallet: (WalletFieldTarget) -> Unit = {},
        onAddCategory: () -> Unit = {},
        onRecurringCostChange: (Int?) -> Unit = {},
        onRecurringIncomeChange: (Int?) -> Unit = {},
    ) {
        composeRule.setContent {
            var modal by remember { mutableStateOf(initial) }
            TransactionModal(
                modal = modal,
                wallets = wallets,
                categories = categories,
                recurringCosts = recurringCosts,
                recurringIncomes = recurringIncomes,
                onTypeChange = { modal = modal.copy(type = it) },
                onAmountChange = { modal = modal.copy(amount = it) },
                onDateChange = { modal = modal.copy(date = it) },
                onWalletChange = { modal = modal.copy(walletId = it) },
                onSourceWalletChange = { modal = modal.copy(sourceWalletId = it) },
                onDestinationWalletChange = { modal = modal.copy(destinationWalletId = it) },
                onCategoryChange = { modal = modal.copy(categoryId = it) },
                onRecurringCostChange = { id ->
                    modal = modal.copy(recurringCostId = id)
                    onRecurringCostChange(id)
                },
                onRecurringIncomeChange = { id ->
                    modal = modal.copy(recurringIncomeId = id)
                    onRecurringIncomeChange(id)
                },
                onDescriptionChange = { modal = modal.copy(description = it) },
                onAddWallet = onAddWallet,
                onAddCategory = onAddCategory,
                onSubmit = {},
                onDelete = {},
                onClose = {},
            )
        }
    }

    @Test
    fun the_wallet_select_offers_new_wallet_never_selecting_it() {
        var added: WalletFieldTarget? = null
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
            ),
            wallets = listOf(cash),
            categories = emptyList(),
            onAddWallet = { added = it },
        )

        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).assertIsDisplayed().performClick()

        // Revert-on-pick (ADR-0013): the sentinel never becomes the field's
        // value — it only reports which field wants a new Wallet.
        assertEquals(WalletFieldTarget.WALLET, added)
        composeRule.onNodeWithText("Cash (€100.00)").assertIsDisplayed()
        composeRule.onNodeWithText("Save transaction").assertIsEnabled()
    }

    @Test
    fun the_wallet_sentinel_still_offers_creation_when_no_wallets_exist() {
        var added: WalletFieldTarget? = null
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = null,
            ),
            wallets = emptyList(),
            categories = emptyList(),
            onAddWallet = { added = it },
        )

        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).assertIsDisplayed().performClick()
        assertEquals(WalletFieldTarget.WALLET, added)
    }

    @Test
    fun a_transfers_from_and_to_each_offer_the_new_wallet_sentinel_with_their_own_target() {
        val added = mutableListOf<WalletFieldTarget>()
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.TRANSFER,
                amount = "10.00",
                date = "2026-08-01",
                sourceWalletId = 1,
                destinationWalletId = 2,
            ),
            wallets = listOf(cash, card),
            categories = emptyList(),
            onAddWallet = { added.add(it) },
        )

        composeRule.onNodeWithTag("tx-source").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).performClick()
        composeRule.onNodeWithTag("tx-destination").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).performClick()

        assertEquals(listOf(WalletFieldTarget.SOURCE, WalletFieldTarget.DESTINATION), added)
        // Neither pick changed the draft's wallets.
        composeRule.onNodeWithText("Cash (€100.00)").assertIsDisplayed()
        composeRule.onNodeWithText("Save transaction").assertIsEnabled()
    }

    @Test
    fun the_category_select_offers_new_category_after_none_and_the_options() {
        var added = 0
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                categoryId = null,
            ),
            wallets = listOf(cash),
            categories = listOf(food),
            onAddCategory = { added++ },
        )

        composeRule.onNodeWithTag("tx-category").performClick()
        composeRule.onNodeWithText("None").assertIsDisplayed()
        composeRule.onNodeWithText("🍕 Food").assertIsDisplayed()
        composeRule.onNodeWithText(ADD_CATEGORY_OPTION).assertIsDisplayed().performClick()

        // Revert-on-pick: the Category field stays on None (its prior value).
        assertEquals(1, added)
        composeRule.onNodeWithText("None").assertIsDisplayed()
    }

    @Test
    fun the_wallet_select_is_locked_while_editing_but_the_category_sentinel_stays_live() {
        val transaction = TransactionDto(
            id = 1,
            type = TransactionType.EXPENSE,
            amount = "5.00",
            date = "2026-08-01",
            wallet_id = 1,
            category_id = null,
            description = null,
            created_at = "2026-08-01T10:00:00Z",
        )
        setForm(
            TransactionsViewModel.ModalState(
                editing = transaction,
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                categoryId = null,
            ),
            wallets = listOf(cash),
            categories = listOf(food),
        )

        // The Wallet fields freeze while editing (like the web app's
        // `disabled={isEditing}`): the sentinel is inert — the menu never
        // opens, so no inline creation can happen from a locked field.
        composeRule.onNodeWithTag("tx-wallet").assertIsNotEnabled()
        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).assertDoesNotExist()

        // The Category field stays live while editing, sentinel included.
        composeRule.onNodeWithTag("tx-category").performClick()
        composeRule.onNodeWithText(ADD_CATEGORY_OPTION).assertIsDisplayed()
    }

    @Test
    fun the_save_button_stays_disabled_until_a_positive_amount_is_entered() {
        setForm(
            TransactionsViewModel.ModalState(type = TransactionType.EXPENSE, date = "2026-08-01", walletId = 1),
            wallets = listOf(cash),
            categories = emptyList(),
        )

        composeRule.onNodeWithText("Save transaction").assertIsNotEnabled()

        composeRule.onNodeWithTag("tx-amount").performTextInput("5.00")
        composeRule.onNodeWithText("Save transaction").assertIsEnabled()

        // Zero is not an amount; the gate closes again.
        composeRule.onNodeWithTag("tx-amount").performTextClearance()
        composeRule.onNodeWithTag("tx-amount").performTextInput("0")
        composeRule.onNodeWithText("Save transaction").assertIsNotEnabled()
    }

    @Test
    fun the_save_button_stays_disabled_until_a_wallet_is_selected() {
        setForm(
            TransactionsViewModel.ModalState(type = TransactionType.EXPENSE, amount = "5.00", date = "2026-08-01", walletId = null),
            wallets = listOf(cash),
            categories = emptyList(),
        )

        composeRule.onNodeWithText("Save transaction").assertIsNotEnabled()

        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText("Cash (€100.00)").assertIsDisplayed().performClick()

        composeRule.onNodeWithText("Save transaction").assertIsEnabled()
    }

    @Test
    fun a_transfer_needs_distinct_wallets_and_never_shows_a_category() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.TRANSFER,
                amount = "10.00",
                date = "2026-08-01",
                sourceWalletId = 1,
                destinationWalletId = 1,
            ),
            wallets = listOf(cash, card),
            categories = listOf(food),
        )

        // A Transfer carries no Category field — the form says so.
        composeRule.onNodeWithTag("tx-category").assertDoesNotExist()
        composeRule.onNodeWithText("Transfers never carry a category.").assertIsDisplayed()

        // Same source and destination: Save stays disabled.
        composeRule.onNodeWithText("Save transaction").assertIsNotEnabled()

        composeRule.onNodeWithTag("tx-destination").performClick()
        composeRule.onNodeWithText("Card (€0.00)").performClick()

        composeRule.onNodeWithText("Save transaction").assertIsEnabled()
    }

    @Test
    fun expense_offers_the_contact_wallet_income_never_does() {
        setForm(
            TransactionsViewModel.ModalState(type = TransactionType.EXPENSE, amount = "5.00", date = "2026-08-01", walletId = 1),
            wallets = listOf(cash, marco),
            categories = emptyList(),
        )

        // An Expense may record consumption a Contact paid for (ADR-0017).
        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText("Marco (€0.00)").assertIsDisplayed()
        composeRule.onNodeWithText("Cash (€100.00)").assertIsDisplayed()
        composeRule.onNodeWithText("Cash (€100.00)").performClick()

        // The same draft as an Income must not offer the Contact Wallet.
        composeRule.onNodeWithText("Income").performClick()
        composeRule.onNodeWithText("Incomes can't be recorded on contact wallets.").assertIsDisplayed()
        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText("Cash (€100.00)").assertIsDisplayed()
        composeRule.onNodeWithText("Marco (€0.00)").assertDoesNotExist()
    }

    @Test
    fun the_category_field_lists_only_categories_of_the_transaction_type() {
        val salary = CategoryDto(2, "Salary", CategoryType.INCOME, "💼", "#3b82f6", "2026-08-01T10:00:00Z")
        setForm(
            TransactionsViewModel.ModalState(type = TransactionType.EXPENSE, amount = "5.00", date = "2026-08-01", walletId = 1),
            wallets = listOf(cash),
            categories = listOf(food, salary),
        )

        composeRule.onNodeWithTag("tx-category").performClick()
        composeRule.onNodeWithText("🍕 Food").assertIsDisplayed()
        composeRule.onNodeWithText("💼 Salary").assertDoesNotExist()
    }

    @Test
    fun the_expense_recurring_cost_select_offers_the_definitions_and_names_the_occurrence() {
        val picked = mutableListOf<Int?>()
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "800.00",
                date = "2026-08-01",
                walletId = 1,
            ),
            wallets = listOf(cash),
            categories = emptyList(),
            recurringCosts = listOf(rent),
            onRecurringCostChange = { picked.add(it) },
        )

        // The None option unlinks; each definition pays its oldest Unpaid
        // Occurrence, named under the select (web issue #57).
        composeRule.onNodeWithTag("tx-recurring-cost").performClick()
        composeRule.onNodeWithText("None").assertIsDisplayed()
        composeRule.onNodeWithText("Rent").performClick()

        assertEquals(listOf(1), picked)
        composeRule.onNodeWithText("Pays the occurrence of 2026-08-01.").assertIsDisplayed()
        composeRule.onNodeWithText("Save transaction").assertIsEnabled()

        // Picking None unlinks again (freeing the Occurrence on save).
        composeRule.onNodeWithTag("tx-recurring-cost").performClick()
        composeRule.onNodeWithText("None").performClick()
        assertEquals(listOf(1, null), picked)
    }

    @Test
    fun income_and_transfer_forms_never_carry_the_recurring_cost_field() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.INCOME,
                amount = "100.00",
                date = "2026-08-01",
                walletId = 1,
            ),
            wallets = listOf(cash),
            categories = emptyList(),
            recurringCosts = listOf(rent),
        )
        composeRule.onNodeWithTag("tx-recurring-cost").assertDoesNotExist()
    }

    @Test
    fun the_income_recurring_income_select_offers_the_definitions_and_names_the_occurrence() {
        val picked = mutableListOf<Int?>()
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.INCOME,
                amount = "2500.00",
                date = "2026-08-01",
                walletId = 1,
            ),
            wallets = listOf(cash),
            categories = emptyList(),
            recurringIncomes = listOf(salary),
            onRecurringIncomeChange = { picked.add(it) },
        )

        // The None option unlinks; each definition pays its oldest Unpaid
        // Occurrence, named under the select (web issue #61).
        composeRule.onNodeWithTag("tx-recurring-income").performClick()
        composeRule.onNodeWithText("None").assertIsDisplayed()
        composeRule.onNodeWithText("Salary").performClick()

        assertEquals(listOf(1), picked)
        composeRule.onNodeWithText("Pays the occurrence of 2026-08-01.").assertIsDisplayed()
        composeRule.onNodeWithText("Save transaction").assertIsEnabled()

        // Picking None unlinks again (freeing the Occurrence on save).
        composeRule.onNodeWithTag("tx-recurring-income").performClick()
        composeRule.onNodeWithText("None").performClick()
        assertEquals(listOf(1, null), picked)
    }

    @Test
    fun expense_and_transfer_forms_never_carry_the_recurring_income_field() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "800.00",
                date = "2026-08-01",
                walletId = 1,
            ),
            wallets = listOf(cash, card),
            categories = emptyList(),
            recurringIncomes = listOf(salary),
        )
        composeRule.onNodeWithTag("tx-recurring-income").assertDoesNotExist()

        // The same draft as a Transfer carries no income link either.
        composeRule.onNodeWithText("Transfer").performClick()
        composeRule.onNodeWithTag("tx-recurring-income").assertDoesNotExist()
    }
}
