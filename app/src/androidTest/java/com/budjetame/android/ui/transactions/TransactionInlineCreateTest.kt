package com.budjetame.android.ui.transactions

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The inline-creation flow (ADR-0013/0014, ticket #21) through the composed
 * Transactions screen: the "New wallet…" / "New category…" sentinels stack
 * the entity's create form on the Transaction form, the created entity lands
 * in the open form's originating select, and the draft submits right away —
 * with the wallet/category gateways faked at the screen's seam.
 */
@RunWith(AndroidJUnit4::class)
class TransactionInlineCreateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val walletGateway = FakeWalletGateway()
    private val categoryGateway = FakeCategoryGateway()
    private val transactionGateway = FakeTransactionGateway()

    private fun launchScreen() {
        composeRule.setContent {
            TransactionsScreen(
                transactions = transactionGateway,
                wallets = walletGateway,
                categories = categoryGateway,
            )
        }
    }

    private fun openNewTransaction() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Nothing here yet.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("New transaction").performClick()
    }

    @Test
    fun a_wallet_created_from_the_form_is_selected_and_the_draft_submits() {
        launchScreen()
        openNewTransaction()

        composeRule.onNodeWithTag("tx-amount").performTextInput("12.50")
        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).performClick()

        // The create form is stacked on the Transaction form; the draft
        // survives below it.
        composeRule.onNodeWithText("New wallet").assertIsDisplayed()
        composeRule.onNodeWithTag("tx-amount").assert(hasText("12.50"))

        composeRule.onNodeWithTag("wallet-name").performTextInput("Savings")
        composeRule.onNodeWithText("Create wallet").performClick()

        // The inner form closed and the fresh Wallet is selected in the
        // originating field — the form can be saved as-is.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Savings (€0.00)").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Savings", walletGateway.createdWallet?.name)
        composeRule.onNodeWithText("Save transaction").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Save transaction").fetchSemanticsNodes().isEmpty()
        }
        val draft = transactionGateway.createdDraft
        assertEquals(TransactionType.EXPENSE, draft?.type)
        assertEquals("12.50", draft?.amount)
        assertEquals(2, draft?.walletId)
        assertEquals("Savings", walletGateway.wallets.single { it.id == 2 }.name)
    }

    @Test
    fun a_category_created_from_the_form_is_selected_and_the_draft_submits() {
        launchScreen()
        openNewTransaction()

        composeRule.onNodeWithTag("tx-amount").performTextInput("8.00")
        composeRule.onNodeWithTag("tx-category").performClick()
        composeRule.onNodeWithText(ADD_CATEGORY_OPTION).performClick()

        // Locked to the form's type: the selector is hidden behind the hint.
        composeRule.onNodeWithText("Expense · fixed for this form").assertIsDisplayed()
        composeRule.onNodeWithTag("category-name").performTextInput("Groceries")
        composeRule.onNodeWithText("Create category").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Groceries").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Groceries", categoryGateway.createdCategory?.name)
        composeRule.onNodeWithText("Save transaction").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Save transaction").fetchSemanticsNodes().isEmpty()
        }
        val draft = transactionGateway.createdDraft
        assertEquals(1, draft?.categoryId)
        assertEquals("Groceries", categoryGateway.categories.single { it.id == 1 }.name)
    }

    @Test
    fun an_income_wallet_sentinel_opens_the_restricted_create_form() {
        launchScreen()
        openNewTransaction()

        composeRule.onNodeWithText("Income").performClick()
        composeRule.onNodeWithTag("tx-wallet").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).performClick()

        // Eligibility lock (ADR-0017): only non-Contact types are offered,
        // with the web's caption under the selector.
        composeRule.onNodeWithText("Checking, Credit Card, Cash · fixed for this form")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("wallet-type").performClick()
        composeRule.onNodeWithText("Credit Card").assertIsDisplayed()
        composeRule.onNodeWithText("Cash").assertIsDisplayed()
        composeRule.onNodeWithText("Contact").assertDoesNotExist()
    }

    /** A Wallet create that is real at once: the created Wallet joins the
     * list the screen reads. */
    private class FakeWalletGateway : WalletGateway {
        val wallets = mutableListOf(
            WalletDto(1, "Cash", WalletType.CASH, "100.00", false, "2026-08-01T10:00:00Z"),
        )
        var createdWallet: WalletDto? = null
            private set

        override suspend fun fetchWallets(): List<WalletDto> = wallets

        override suspend fun createWallet(
            name: String,
            type: WalletType,
            openingBalance: String,
        ): WalletDto {
            val created = WalletDto(2, name, type, openingBalance, false, "2026-08-01T10:00:00Z")
            createdWallet = created
            wallets.add(created)
            return created
        }

        override suspend fun renameWallet(id: Int, name: String): WalletDto = error("unused")
        override suspend fun freezeWallet(id: Int) = error("unused")
        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused")
    }

    /** A Category create that is real at once. */
    private class FakeCategoryGateway : CategoryGateway {
        val categories = mutableListOf<CategoryDto>()
        var createdCategory: CategoryDto? = null
            private set

        override suspend fun fetchCategories(): List<CategoryDto> = categories

        override suspend fun createCategory(
            name: String,
            type: CategoryType,
            icon: String,
            color: String,
        ): CategoryDto {
            val created = CategoryDto(1, name, type, icon.ifBlank { null }, color, "2026-08-01T10:00:00Z")
            createdCategory = created
            categories.add(created)
            return created
        }

        override suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto = error("unused")
        override suspend fun deleteCategory(id: Int) = error("unused")
    }

    /** The ledger: empty until a Transaction is created through the form. */
    private class FakeTransactionGateway : TransactionGateway {
        var createdDraft: TransactionDraft? = null
            private set

        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto = TransactionPageDto(emptyList(), null)

        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto {
            createdDraft = draft
            return TransactionDto(
                id = 1,
                type = draft.type,
                amount = draft.amount,
                date = draft.date,
                wallet_id = draft.walletId,
                source_wallet_id = draft.sourceWalletId,
                destination_wallet_id = draft.destinationWalletId,
                category_id = draft.categoryId,
                description = draft.description,
                created_at = "2026-08-01T10:00:00Z",
            )
        }

        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
            error("unused")
        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto = error("unused")
    }
}
