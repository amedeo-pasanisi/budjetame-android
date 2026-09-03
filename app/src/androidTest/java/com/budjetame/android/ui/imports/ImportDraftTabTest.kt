package com.budjetame.android.ui.imports

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
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.shell.AppShell
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Import Draft survives tab switches (web issue #43, ticket #26, #41):
 * the draft lives in the Transactions tab's ViewModel, which the shell's
 * Activity-scoped store keeps while the pager disposes the page — switching
 * to the Wallets tab and back resumes the flow exactly where it was left.
 * The only discard paths are Cancel, picking another file, and a successful
 * import; the shell is rendered with trivial in-memory gateways, and the
 * flow is driven as far as the pick phase (the system file picker itself
 * is not drivable in tests).
 */
@RunWith(AndroidJUnit4::class)
class ImportDraftTabTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchShell() {
        composeRule.setContent {
            AppShell(
                account = AccountDto(id = 1, email = "test@budjetame.de"),
                walletRepository = FakeWalletGateway(),
                categoryRepository = FakeCategoryGateway(),
                dashboardRepository = FakeDashboardGateway(),
                transactionRepository = FakeTransactionGateway(),
                importRepository = FakeImportGateway(),
                recurringCostRepository = FakeRecurringCostGateway(),
                recurringIncomeRepository = FakeRecurringIncomeGateway(),
                location = SilentLocation(),
                onSignOut = {},
                onDeleteAccount = {},
            )
        }
    }

    @Test
    fun `the import draft survives a tab switch and only cancel discards it`() {
        launchShell()

        // The shell opens on the Dashboard; the test drives the whole flow
        // from the Transactions tab. (A tab tap glides the pager there —
        // the same motion a drag would end in, ADR-0003.)
        composeRule.onNodeWithText("Transactions").performClick()

        // The Transactions tab's ledger loads, then Import opens the pick
        // phase — the flow's own header replaces the ledger's.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("New transaction").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Import").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Read and validate").fetchSemanticsNodes().isNotEmpty()
        }

        // Switch to the Wallets tab and back: the draft — pick phase, no
        // file — is still open, not a fresh ledger.
        composeRule.onNodeWithText("Wallets").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("New wallet").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Transactions").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Choose file").fetchSemanticsNodes().isNotEmpty()
        }

        // Cancel is the discard path: the ledger returns.
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("New transaction").fetchSemanticsNodes().isNotEmpty()
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

    private class FakeTransactionGateway : TransactionGateway {
        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto = TransactionPageDto(items = emptyList(), next_cursor = null)
        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto = error("unused")
        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto = error("unused")
        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto = error("unused")
        override suspend fun export(filters: TransactionFilters): ExportFile = error("unused")
    }

    private class FakeRecurringCostGateway : RecurringCostGateway {
        override suspend fun fetchRecurringCosts(): List<RecurringCostDto> = emptyList()
        override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto = error("unused")
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
private class SilentLocation : DeviceLocation {
    override fun permissionGranted(): Boolean = true

    override suspend fun currentPosition(): LatLng? = null
}
