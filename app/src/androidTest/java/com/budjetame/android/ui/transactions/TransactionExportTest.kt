package com.budjetame.android.ui.transactions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowRevalidationDto
import com.budjetame.android.data.api.ImportRowValidationDto
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.shell.AppShell
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Export button's wiring (US 7.3, ticket #28) through the composed
 * Transactions screen: the header's Export press runs the export through
 * the gateway, and a failed export surfaces the web screen's error line.
 * The happy path stops at the gateway seam here by design — the file's
 * journey from the response to the system share sheet is the seam test's
 * (the mapping is driven through MockWebServer) and the share sheet itself
 * is not drivable in tests, exactly like the import file picker.
 */
@RunWith(AndroidJUnit4::class)
class TransactionExportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchShell(transactions: FakeTransactionGateway) {
        composeRule.setContent {
            AppShell(
                account = AccountDto(id = 1, email = "test@budjetame.de"),
                walletRepository = FakeWalletGateway(),
                categoryRepository = FakeCategoryGateway(),
                dashboardRepository = FakeDashboardGateway(),
                transactionRepository = transactions,
                importRepository = FakeImportGateway(),
                recurringCostRepository = FakeRecurringCostGateway(),
                recurringIncomeRepository = FakeRecurringIncomeGateway(),
                onSignOut = {},
                onDeleteAccount = {},
            )
        }
    }

    @Test
    fun `a failed export surfaces the web's error line on the ledger`() {
        launchShell(FakeTransactionGateway())

        // The ledger loads and the header carries the Export button next
        // to Import (the web screen's shape).
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Export").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("Export").performClick()

        // The failure speaks the web screen's copy in the export error line.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Could not export transactions.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The ledger itself is untouched by the failed export.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("All transactions").fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- Trivial in-memory gateways -----------------------------------------

    private class FakeWalletGateway : WalletGateway {
        override suspend fun fetchWallets(): List<WalletDto> = emptyList()
        override suspend fun createWallet(name: String, type: WalletType, openingBalance: String): WalletDto =
            error("unused")
        override suspend fun renameWallet(id: Int, name: String): WalletDto = error("unused")
        override suspend fun freezeWallet(id: Int) = error("unused")
        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused")
    }

    private class FakeCategoryGateway : CategoryGateway {
        override suspend fun fetchCategories(): List<CategoryDto> = emptyList()
        override suspend fun createCategory(name: String, type: CategoryType, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto = error("unused")
        override suspend fun deleteCategory(id: Int) = error("unused")
    }

    private class FakeDashboardGateway : DashboardGateway {
        override suspend fun fetchSummary(month: String): DashboardSummaryDto =
            DashboardSummaryDto(
                net_worth = "0.00",
                month = month,
                income = "0.00",
                expenses = "0.00",
                expenses_by_category = emptyList(),
                incomes_by_category = emptyList(),
            )
        override suspend fun fetchTrend(kind: TrendKind, fromMonth: String, toMonth: String): TrendDto =
            TrendDto(from_month = fromMonth, to_month = toMonth, months = emptyList())
        override suspend fun fetchBudget(): BudgetDto =
            BudgetDto(month = "2026-08", monthly_spendable = "0.00", daily_allowance = "0.00", spendable_today = "0.00")
    }

    /** Every export fails: the header press must surface the error line —
     * the share sheet itself is not drivable in tests. */
    private class FakeTransactionGateway : TransactionGateway {
        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto = TransactionPageDto(items = emptyList(), next_cursor = null)
        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto = error("unused")
        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto = error("unused")
        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto = error("unused")
        override suspend fun export(filters: TransactionFilters): ExportFile = error("boom")
    }

    private class FakeRecurringCostGateway : RecurringCostGateway {
        override suspend fun fetchRecurringCosts(): List<RecurringCostDto> = emptyList()
        override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto =
            error("unused")
        override suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto =
            error("unused")
        override suspend fun deleteRecurringCost(id: Int) = error("unused")
        override suspend fun toggleSkipRecurringCost(id: Int): RecurringCostDto = error("unused")
    }

    private class FakeRecurringIncomeGateway : RecurringIncomeGateway {
        override suspend fun fetchRecurringIncomes(): List<RecurringIncomeDto> = emptyList()
        override suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused")
        override suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused")
        override suspend fun deleteRecurringIncome(id: Int) = error("unused")
        override suspend fun toggleSkipRecurringIncome(id: Int): RecurringIncomeDto = error("unused")
    }

    private class FakeImportGateway : ImportGateway {
        override suspend fun preview(fileName: String, content: ByteArray): ImportPreviewDto = error("unused")
        override suspend fun validateRow(
            row: ImportRowInput,
            earlierRows: List<ImportRowInput>,
        ): ImportRowValidationDto = error("unused")
        override suspend fun revalidateRows(
            rows: List<ImportRowInput>,
            targets: List<Int>,
        ): List<ImportRowRevalidationDto> = error("unused")
        override suspend fun confirm(rows: List<ImportRowInput>): List<TransactionDto> = error("unused")
    }
}
