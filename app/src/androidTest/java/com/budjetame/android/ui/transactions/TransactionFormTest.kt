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
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
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

    private fun setForm(
        initial: TransactionsViewModel.ModalState,
        wallets: List<WalletDto>,
        categories: List<CategoryDto>,
    ) {
        composeRule.setContent {
            var modal by remember { mutableStateOf(initial) }
            TransactionModal(
                modal = modal,
                wallets = wallets,
                categories = categories,
                onTypeChange = { modal = modal.copy(type = it) },
                onAmountChange = { modal = modal.copy(amount = it) },
                onDateChange = { modal = modal.copy(date = it) },
                onWalletChange = { modal = modal.copy(walletId = it) },
                onSourceWalletChange = { modal = modal.copy(sourceWalletId = it) },
                onDestinationWalletChange = { modal = modal.copy(destinationWalletId = it) },
                onCategoryChange = { modal = modal.copy(categoryId = it) },
                onDescriptionChange = { modal = modal.copy(description = it) },
                onSubmit = {},
                onDelete = {},
                onClose = {},
            )
        }
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
}
