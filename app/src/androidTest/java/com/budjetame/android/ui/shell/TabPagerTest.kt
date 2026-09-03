package com.budjetame.android.ui.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
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
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell's swipeable tabs (ADR-0003, ticket #41) through the composed
 * AppShell: dragging the content moves between tabs (a release snaps to the
 * nearest tab, a fling crosses one), the bottom bar's selection follows the
 * pages, a bottom-tab tap glides to its page, and a revisited tab renders
 * its held rows and scroll position instantly — the pages' ViewModels and
 * per-tab saveable state survive page disposal. The shell is rendered with
 * trivial in-memory gateways; the drags start on the pager's top strip (the
 * pages' header padding), never inside a page's own scrollable card.
 */
@RunWith(AndroidJUnit4::class)
class TabPagerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchShell(
        walletRepository: WalletGateway = EmptyWalletGateway(),
        transactionRepository: TransactionGateway = EmptyTransactionGateway(),
    ) {
        composeRule.setContent {
            AppShell(
                account = AccountDto(id = 1, email = "test@budjetame.de"),
                walletRepository = walletRepository,
                categoryRepository = EmptyCategoryGateway(),
                dashboardRepository = EmptyDashboardGateway(),
                transactionRepository = transactionRepository,
                importRepository = EmptyImportGateway(),
                recurringCostRepository = EmptyRecurringCostGateway(),
                recurringIncomeRepository = EmptyRecurringIncomeGateway(),
                location = ShellSilentLocation(),
                onSignOut = {},
                onDeleteAccount = {},
            )
        }
    }

    /** The dashboard page is up once the Net Worth card's label exists. */
    private fun waitForDashboard() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("NET WORTH").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The bottom bar's item for [label] — the selectable item node, whose
     * descendant carries the label text (the pages' own headers never
     * match: they are not selectable). */
    private fun bottomTab(label: String): SemanticsNodeInteraction =
        composeRule.onNode(isSelectable() and hasAnyDescendant(hasText(label)))

    /** A fast full-width left swipe along the pager's top strip — a fling
     * across exactly one tab. */
    private fun flingLeft() {
        composeRule.onNodeWithTag("tab-pager").performTouchInput {
            swipe(
                start = Offset(width - 40f, 8f),
                end = Offset(40f, 8f),
                durationMillis = 200,
            )
        }
    }

    @Test
    fun `a left fling crosses to the next tab and the bottom bar follows`() {
        launchShell()
        waitForDashboard()

        // A fling crosses exactly one tab: the Wallets page shows, the
        // dashboard page is disposed, and the bar's Wallets item is the
        // selected one.
        flingLeft()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No wallets yet. Add your first one to start tracking.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("NET WORTH").fetchSemanticsNodes().isEmpty()
        }
        bottomTab("Wallets").assertIsSelected()
    }

    @Test
    fun `a drag released before the halfway point snaps back to the tab it left`() {
        launchShell()
        waitForDashboard()

        // A slow drag of about a third of the width and a release: the
        // finger-following page returns to the nearest tab — the dashboard
        // — and the bar's Dashboard item stays selected once the transient
        // Wallets page is disposed.
        composeRule.onNodeWithTag("tab-pager").performTouchInput {
            swipe(
                start = Offset(width * 0.8f, 8f),
                end = Offset(width * 0.5f, 8f),
                durationMillis = 800,
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No wallets yet. Add your first one to start tracking.")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("NET WORTH").assertIsDisplayed()
        bottomTab("Dashboard").assertIsSelected()
    }

    @Test
    fun `two flings cross one tab each and a bottom-tab tap glides to its page`() {
        launchShell()
        waitForDashboard()

        // Two flings: Dashboard → Wallets → Categories — each fling lands
        // on the neighbor page, never skipping one.
        flingLeft()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No wallets yet. Add your first one to start tracking.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The bar's selection is read only once the pager has settled — a
        // page's content composes as soon as it enters the viewport, while
        // currentPage only moves past the halfway point.
        flingLeft()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No categories yet. Add one to start grouping your transactions.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No wallets yet. Add your first one to start tracking.")
                .fetchSemanticsNodes().isEmpty()
        }
        bottomTab("Categories").assertIsSelected()

        // A bottom-tab tap glides to its page (the Recurring tab's toggle
        // row is its content marker) and lights the bar's item up.
        composeRule.onNodeWithText("Recurring").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Incomes").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("No categories yet. Add one to start grouping your transactions.")
                .fetchSemanticsNodes().isEmpty()
        }
        bottomTab("Recurring").assertIsSelected()
    }

    @Test
    fun `revisiting a tab shows its held rows and scroll position without refetching`() {
        val transactions = LedgerTransactionGateway(rows = 30)
        launchShell(
            walletRepository = CashWalletGateway(),
            transactionRepository = transactions,
        )

        // Open the Transactions tab: the ledger loads its first page once,
        // rows 01…30 in order.
        composeRule.onNodeWithText("Transactions").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Row 01").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, transactions.fetchCount)

        // Scroll deep into the ledger: the top rows leave the composition.
        repeat(4) {
            composeRule.onNodeWithTag("tab-pager").performTouchInput { swipeUp() }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Row 30").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Row 01").fetchSemanticsNodes().isEmpty())
        assertEquals(1, transactions.fetchCount)

        // Leave the tab and come back: the rows render instantly from the
        // held ViewModel (no refetch) and the ledger is still scrolled to
        // the same place — the per-tab state survived the page disposal.
        composeRule.onNodeWithText("Dashboard").performClick()
        waitForDashboard()
        composeRule.onNodeWithText("Transactions").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Row 30").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, transactions.fetchCount)
        assertTrue(composeRule.onAllNodesWithText("Row 01").fetchSemanticsNodes().isEmpty())
    }

    // --- Trivial in-memory gateways -----------------------------------------

    private class CashWalletGateway : WalletGateway {
        override suspend fun fetchWallets(): List<WalletDto> =
            listOf(WalletDto(1, "Cash", WalletType.CASH, "0.00", false, "2026-08-01T10:00:00Z"))
        override suspend fun createWallet(name: String, type: WalletType, openingBalance: String): WalletDto =
            error("unused")
        override suspend fun renameWallet(id: Int, name: String): WalletDto = error("unused")
        override suspend fun freezeWallet(id: Int) = error("unused")
        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused")
    }

    private class EmptyWalletGateway : WalletGateway {
        override suspend fun fetchWallets(): List<WalletDto> = emptyList()
        override suspend fun createWallet(name: String, type: WalletType, openingBalance: String): WalletDto =
            error("unused")
        override suspend fun renameWallet(id: Int, name: String): WalletDto = error("unused")
        override suspend fun freezeWallet(id: Int) = error("unused")
        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused")
    }

    private class EmptyCategoryGateway : CategoryGateway {
        override suspend fun fetchCategories(): List<CategoryDto> = emptyList()
        override suspend fun createCategory(name: String, type: CategoryType, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto = error("unused")
        override suspend fun deleteCategory(id: Int) = error("unused")
    }

    private class EmptyDashboardGateway : DashboardGateway {
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

    /** The ledger over a seeded list of `Row 01`…`Row NN` expenses on the
     * Cash wallet, counting every page fetch — one fetch per ViewModel
     * lifetime is the state-retention proof. */
    private class LedgerTransactionGateway(rows: Int) : TransactionGateway {
        val rows: List<TransactionDto> = (1..rows).map { index ->
            TransactionDto(
                id = index,
                type = TransactionType.EXPENSE,
                amount = "1.00",
                date = "2026-08-01",
                wallet_id = 1,
                description = "Row ${index.toString().padStart(2, '0')}",
                created_at = "2026-08-01T10:00:00Z",
            )
        }
        var fetchCount = 0

        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto {
            fetchCount++
            return TransactionPageDto(items = rows, next_cursor = null)
        }

        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto =
            error("unused")
        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
            error("unused")
        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto =
            error("unused")
        override suspend fun export(filters: TransactionFilters): ExportFile = error("unused")
    }

    private class EmptyTransactionGateway : TransactionGateway {
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

    private class EmptyRecurringCostGateway : RecurringCostGateway {
        override suspend fun fetchRecurringCosts(): List<RecurringCostDto> = emptyList()
        override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto = error("unused")
        override suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto =
            error("unused")
        override suspend fun deleteRecurringCost(id: Int) = error("unused")
        override suspend fun toggleSkipRecurringCost(id: Int): RecurringCostDto = error("unused")
    }

    private class EmptyRecurringIncomeGateway : RecurringIncomeGateway {
        override suspend fun fetchRecurringIncomes(): List<RecurringIncomeDto> = emptyList()
        override suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused")
        override suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused")
        override suspend fun deleteRecurringIncome(id: Int) = error("unused")
        override suspend fun toggleSkipRecurringIncome(id: Int): RecurringIncomeDto = error("unused")
    }

    private class EmptyImportGateway : ImportGateway {
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
 * held, no position — no system prompt can ever be raised. */
private class ShellSilentLocation : DeviceLocation {
    override fun permissionGranted(): Boolean = true

    override suspend fun currentPosition(): LatLng? = null
}
