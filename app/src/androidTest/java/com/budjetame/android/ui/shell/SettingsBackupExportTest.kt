package com.budjetame.android.ui.shell

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
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RestoreResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.auth.LocaleGateway
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.recurringcost.RecurringCostDraft
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeDraft
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings Export all entry point's wiring (ticket #57) through
 * the composed AppShell: the Settings button opens the dialog, Export all
 * is present in it, and a tap surfaces the error line when the gateway
 * throws (the share sheet itself is not drivable in tests, exactly like
 * the ledger export). The happy path stops at the gateway seam here by
 * design — the file's journey from the response to the system share sheet
 * is the unit seam's.
 */
@RunWith(AndroidJUnit4::class)
class SettingsBackupExportTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchShell(backup: FakeBackupGateway) {
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
                backupRepository = backup,
                localeRepository = FakeLocaleGateway(),
                location = SilentLocation(),
                onSignOut = {},
                onDeleteAccount = {},
            )
        }
    }

    private fun openSettings() {
        // The settings gear icon in the header. Clicking it opens the
        // Settings dialog.
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Settings").fetchSemanticsNodes().size >= 2
        }
    }

    @Test
    fun `Export all button is present in the Settings dialog`() {
        launchShell(FakeBackupGateway())
        openSettings()

        // The dialog title is "Settings", and the Export all label and
        // button text are present.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Export all").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `Export all press surfaces the error line when the gateway fails`() {
        launchShell(FakeBackupGateway())
        openSettings()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Export all").fetchSemanticsNodes().isNotEmpty()
        }
        // The first "Export all" node is the button (label before divider).
        composeRule.onNodeWithText("Export all").performClick()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Could not export the backup workbook.")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // --- Trivial in-memory gateways -----------------------------------------

    private class FakeBackupGateway : BackupGateway {
        override suspend fun exportBackup(): ExportFile = error("boom")
        override suspend fun restoreBackup(fileName: String, content: ByteArray): RestoreResultDto = error("boom")
    }

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
        override suspend fun fetchBudget(month: String?): BudgetDto =
            BudgetDto(month = "2026-08", monthly_spendable = "0.00", recurring_incomes_total = "0.00", recurring_costs_total = "0.00", daily_allowance = "0.00", spendable_today = "0.00", remaining_monthly_spendable = "0.00")
    }

    private class FakeTransactionGateway : TransactionGateway {
        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto = TransactionPageDto(
            items = listOf(
                TransactionDto(
                    id = 1,
                    type = com.budjetame.android.data.api.TransactionType.EXPENSE,
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
        override suspend fun deleteTransaction(id: Int): TransactionDto = error("unused")
        override suspend fun undoTransaction(transaction: TransactionDto): TransactionDto = error("unused")
        override suspend fun export(filters: TransactionFilters): ExportFile = error("unused")
    }

    private class FakeRecurringCostGateway : RecurringCostGateway {
        override suspend fun fetchRecurringCosts(includeFrozen: Boolean): List<RecurringCostDto> = emptyList()
        override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto =
            error("unused")
        override suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto =
            error("unused")
        override suspend fun freezeRecurringCost(id: Int): RecurringCostDto = error("unused")
        override suspend fun unfreezeRecurringCost(id: Int): RecurringCostDto = error("unused")
        override suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto> = error("unused")
        override suspend fun setOccurrenceSkipped(id: Int, occurrenceDate: String, skipped: Boolean): List<RecurringOccurrenceDto> = error("unused")
    }

    private class FakeRecurringIncomeGateway : RecurringIncomeGateway {
        override suspend fun fetchRecurringIncomes(includeFrozen: Boolean): List<RecurringIncomeDto> = emptyList()
        override suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused")
        override suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto =
            error("unused")
        override suspend fun freezeRecurringIncome(id: Int): RecurringIncomeDto = error("unused")
        override suspend fun unfreezeRecurringIncome(id: Int): RecurringIncomeDto = error("unused")
        override suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto> = error("unused")
        override suspend fun setOccurrenceSkipped(id: Int, occurrenceDate: String, skipped: Boolean): List<RecurringOccurrenceDto> = error("unused")
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

private class FakeLocaleGateway : LocaleGateway {
    override suspend fun fetchLocale(): String = "en"
    override suspend fun updateLocale(tag: String) {}
}