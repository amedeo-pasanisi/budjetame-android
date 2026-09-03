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
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.transaction.LatLng
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Export entry points' wiring (US 7.3, ticket #28, chrome web issue
 * #92 / ticket #35) through the composed Transactions screen: the header no
 * longer carries an Export button — the web's two places, the filtered
 * chips line and the filter panel's footer, do — and a failed export
 * surfaces the web screen's error line from either. The happy path stops
 * at the gateway seam here by design — the file's journey from the
 * response to the system share sheet is the seam test's (the mapping is
 * driven through MockWebServer) and the share sheet itself is not drivable
 * in tests, exactly like the import file picker.
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
                location = ExportSilentLocation(),
                onSignOut = {},
                onDeleteAccount = {},
            )
        }
    }

    /** The ledger is loaded once the toolbar's Filters toggle is up (the
     * row below proves the ledger itself). */
    private fun waitForLedger() {
        // The shell opens on the Dashboard; these tests drive the whole
        // flow from the Transactions tab (a tab tap glides the pager
        // there, ADR-0003).
        composeRule.onNodeWithText("Transactions").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Filters ▸").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `the panel footer's Export to Excel press surfaces the web's error line`() {
        launchShell(FakeTransactionGateway())
        waitForLedger()

        // The header carries no export path at all: no Export to Excel
        // anywhere on the bare ledger, no old-style "Export" label, and
        // the "All transactions" label row is gone too.
        assertEquals(0, composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("Export").fetchSemanticsNodes().size)
        assertEquals(0, composeRule.onAllNodesWithText("All transactions").fetchSemanticsNodes().size)

        // The filter panel's footer is one of the web's two Export entry
        // points: Export to Excel is always there while the panel is open,
        // even with no filter set — the full-ledger export path.
        composeRule.onNodeWithText("Filters ▸").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().isNotEmpty()
        }
        // With nothing set the footer shows no Clear all filters.
        assertEquals(0, composeRule.onAllNodesWithText("Clear all filters").fetchSemanticsNodes().size)
        composeRule.onNodeWithText("Export to Excel").performClick()

        // The failure speaks the web screen's copy in the export error
        // line.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Could not export transactions.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The ledger itself is untouched by the failed export: the row is
        // still there and the panel still offers the retry press.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Uncategorized · Coffee").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size)
    }

    @Test
    fun `the filtered line's Export to Excel press surfaces the same error line`() {
        launchShell(FakeTransactionGateway())
        waitForLedger()

        // Set a wallet filter through the panel: the chips line appears
        // with its own Clear all and Export to Excel — the panel's footer
        // carries the second copy while it stays open.
        composeRule.onNodeWithText("Filters ▸").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("All wallets").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All wallets").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Cash (€0.00)").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Cash (€0.00)").performClick()

        // The chip names the filter; both entry points are now on screen.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(2, composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size)

        // Closing the panel leaves the filtered line's own Export as the
        // single entry point — export without the panel, like the web.
        composeRule.onNodeWithText("Filters ▾").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Export to Excel").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Could not export transactions.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The chip line and the ledger survive the failed export.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Cash").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().size)
    }

    // --- Trivial in-memory gateways -----------------------------------------

    private class FakeWalletGateway : WalletGateway {
        override suspend fun fetchWallets(): List<WalletDto> =
            listOf(WalletDto(1, "Cash", WalletType.CASH, "0.00", false, "2026-08-01T10:00:00Z"))
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

    /** Every export fails — the presses must surface the error line (the
     * share sheet itself is not drivable in tests); the ledger holds one
     * Coffee row so the chrome around it is the real screen's. */
    private class FakeTransactionGateway : TransactionGateway {
        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto = TransactionPageDto(
            items = listOf(
                TransactionDto(
                    id = 1,
                    type = TransactionType.EXPENSE,
                    amount = "5.00",
                    date = "2026-08-01",
                    wallet_id = 1,
                    description = "Coffee",
                    created_at = "2026-08-01T10:00:00Z",
                ),
            ),
            next_cursor = null,
        )
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

/** The device GPS for the shell tests (ticket #29): permission already
 * held, no position — a save without a location never raises the system
 * permission prompt these tests must not trigger, and never attaches one.
 */
private class ExportSilentLocation : DeviceLocation {
    override fun permissionGranted(): Boolean = true

    override suspend fun currentPosition(): LatLng? = null
}
