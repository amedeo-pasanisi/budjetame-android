package com.budjetame.android.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowRevalidationDto
import com.budjetame.android.data.api.ImportRowValidationDto
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.CategoryGateway
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
import com.budjetame.android.util.Dates
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Transactions chrome's web v1.2.0 shape (web issue #92, ticket #35)
 * through the composed screen: the header row (title + Import + New
 * transaction — no Export), the toolbar row (search with the Filters
 * toggle at its right, hidden on a truly empty ledger), the filtered chips
 * line (per-filter ✕, Clear all, Export to Excel) and the filter panel's
 * footer (Clear all filters + Export to Excel). The screen is composed
 * directly with trivial in-memory gateways, like the form tests.
 */
@RunWith(AndroidJUnit4::class)
class TransactionsChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val transactions = FakeTransactionGateway()
    private val imports = FakeImportGateway()
    private val wallets = FakeWalletGateway()
    private val categories = FakeCategoryGateway()
    private val recurringCosts = FakeRecurringCostGateway()
    private val recurringIncomes = FakeRecurringIncomeGateway()

    private fun launchScreen() {
        composeRule.setContent {
            TransactionsScreen(
                transactions = transactions,
                imports = imports,
                wallets = wallets,
                categories = categories,
                recurringCosts = recurringCosts,
                recurringIncomes = recurringIncomes,
                location = ChromeSilentLocation(),
            )
        }
    }

    /** The ledger is loaded and its toolbar is up once the Filters toggle
     * exists. */
    private fun waitForLedger() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Filters ▸").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openFilters() {
        composeRule.onNodeWithText("Filters ▸").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Filters ▾").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeFilters() {
        composeRule.onNodeWithText("Filters ▾").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Filters ▸").fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Pick the Cash wallet in the filter panel's Wallet select. */
    private fun pickWalletFilter() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("All wallets").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All wallets").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Cash (€0.00)").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Cash (€0.00)").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove Cash filter")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The search box's current text, read from its editable-text
     * semantics. */
    private fun searchText(): String {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("tx-search").fetchSemanticsNodes().isNotEmpty()
        }
        return composeRule.onNodeWithTag("tx-search")
            .fetchSemanticsNode().config[SemanticsProperties.EditableText]?.text.orEmpty()
    }

    @Test
    fun `the header row is title plus Import and New transaction with no export and the toolbar puts the toggle right of the search`() {
        launchScreen()
        waitForLedger()

        // The header holds exactly the web's three: the title, Import as a
        // plain text action, New transaction filled — no Export anywhere on
        // the bare ledger, and no old-style "Export" label either.
        assertTrue(composeRule.onAllNodesWithText("Transactions").fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithText("Import").assertIsDisplayed()
        composeRule.onNodeWithText("New transaction").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Export").fetchSemanticsNodes().isEmpty())

        // The toolbar row: the search field takes the width and the Filters
        // toggle sits at its right.
        val search = composeRule.onNodeWithTag("tx-search")
        val toggle = composeRule.onNodeWithText("Filters ▸")
        assertTrue(search.getUnclippedBoundsInRoot().left < toggle.getUnclippedBoundsInRoot().left)
    }

    @Test
    fun `a truly empty ledger hides the whole toolbar but keeps the header`() {
        transactions.rows = emptyList()
        launchScreen()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Nothing here yet.").fetchSemanticsNodes().isNotEmpty()
        }
        // The whole toolbar row hides: no search field, no Filters toggle —
        // with nothing to search or filter there is no chrome to act on.
        assertTrue(composeRule.onAllNodesWithTag("tx-search").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Filters ▸").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().isEmpty())
        // The header row stays: New transaction and Import remain reachable.
        composeRule.onNodeWithText("New transaction").assertIsDisplayed()
        composeRule.onNodeWithText("Import").assertIsDisplayed()
    }

    @Test
    fun `the header row fits at 360dp with a 1_3 font scale and the title never wraps mid-word`() {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 1.3f),
            ) {
                Box(modifier = Modifier.width(360.dp)) {
                    TransactionsScreen(
                        transactions = transactions,
                        imports = imports,
                        wallets = wallets,
                        categories = categories,
                        recurringCosts = recurringCosts,
                        recurringIncomes = recurringIncomes,
                        location = ChromeSilentLocation(),
                    )
                }
            }
        }

        // Wait for the ledger under the header.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Uncategorized · Lunch").fetchSemanticsNodes().isNotEmpty()
        }

        // The title keeps its natural width and one line: the old squeeze
        // that wrapped it mid-word made it several lines tall and narrow.
        // One titleLarge line is ≈ 28sp at the forced 1.3 scale; a wrapped
        // title is at least two.
        val title = composeRule.onNodeWithText("Transactions")
        val titleBounds = title.getUnclippedBoundsInRoot()
        assertTrue(
            "the title should stay on one line, was ${titleBounds.height} tall",
            titleBounds.height < 28.dp * 1.3f * 1.6f,
        )
        assertTrue(
            "the title should keep its natural width, was ${titleBounds.width}",
            titleBounds.width > 100.dp,
        )
        // Every action is displayed whole — a FlowRow wraps an action to a
        // second line rather than clipping or breaking its label.
        composeRule.onNodeWithText("Import").assertIsDisplayed()
        composeRule.onNodeWithText("New transaction").assertIsDisplayed()
    }

    @Test
    fun `a set filter shows its chip and each chip's x removes just that filter`() {
        launchScreen()
        waitForLedger()
        openFilters()

        // The Wallet, Category, and Recurring selects set three filters;
        // each pick raises its chip on the filtered line.
        pickWalletFilter()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("All categories").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All categories").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("🍕 Food").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("🍕 Food").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove Food filter")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("All transactions").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All transactions").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Rent").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Rent").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove Rent filter")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The chips line shows the three chips with plain names (no
        // balance, no icon), each with its ✕; Clear all and Export to
        // Excel sit on the right, and the panel's footer shows Clear all
        // filters plus its own Export to Excel.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Cash").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Food").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Rent").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Clear all filters").fetchSemanticsNodes().isNotEmpty())
        assertEquals(2, composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size)

        // A chip's ✕ removes just that filter: Rent goes, Cash and Food
        // stay; then Food; then Cash — the last removal takes the whole
        // line away (no filter set any more), leaving only the panel's
        // footer Export.
        composeRule.onNodeWithContentDescription("Remove Rent filter").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove Rent filter")
                .fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithContentDescription("Remove Cash filter")
            .fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithContentDescription("Remove Food filter")
            .fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithContentDescription("Remove Food filter").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove Food filter")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("Remove Cash filter").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("Clear all filters").fetchSemanticsNodes().isEmpty())
        assertEquals(1, composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size)
    }

    @Test
    fun `the chips line Clear all also clears the search while the panel footer's Clear all filters leaves it`() {
        launchScreen()
        waitForLedger()

        // A search alone never shows the chips line: the search text lives
        // in the box, not in a chip.
        composeRule.onNodeWithTag("tx-search").performTextInput("coffee")
        composeRule.waitUntil(5_000) { searchText() == "coffee" }
        assertTrue(composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isEmpty())

        // A wallet filter brings the line up: one chip for the wallet —
        // never one for the search text.
        openFilters()
        pickWalletFilter()
        closeFilters()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithContentDescription("Remove coffee filter")
            .fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithContentDescription("Remove Cash filter")
            .fetchSemanticsNodes().isNotEmpty())

        // The line's Clear all removes the filter AND the search text in
        // one tap: the line is gone and the box is empty.
        composeRule.onNodeWithText("Clear all").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isEmpty() &&
                searchText().isEmpty()
        }

        // Set the filter and the search again, then clear from the panel
        // footer: Clear all filters takes the five filters only — the
        // search box keeps its text.
        openFilters()
        pickWalletFilter()
        composeRule.onNodeWithTag("tx-search").performTextInput("coffee")
        composeRule.waitUntil(5_000) { searchText() == "coffee" }
        composeRule.onNodeWithText("Clear all filters").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Clear all").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("Clear all filters").fetchSemanticsNodes().isEmpty()
        }
        assertEquals("coffee", searchText())
        assertEquals(1, composeRule.onAllNodesWithText("Export to Excel").fetchSemanticsNodes().size)
    }

    @Test
    fun `the date range chips From and To merge into one chip that clears both sides`() {
        launchScreen()
        waitForLedger()
        openFilters()

        // A From bound alone chips as "From …".
        val expected = Dates.toApiDay(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(15))
        composeRule.onAllNodesWithText("Any date").onFirst().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("OK").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("15").performClick()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove From $expected filter")
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Adding To merges both bounds into the single range chip — one
        // chip, one ✕, one date-range filter.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Any date").fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("Any date").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("OK").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("15").performClick()
        composeRule.onNodeWithText("OK").performClick()
        val merged = "$expected – $expected"
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(merged).fetchSemanticsNodes().isNotEmpty()
        }

        // The merged chip's ✕ resets both sides together: no From chip, no
        // To chip, and both fields are back to "Any date".
        composeRule.onNodeWithContentDescription("Remove $merged filter").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("Remove $merged filter")
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Any date").fetchSemanticsNodes().size == 2
        }
    }

    // --- Trivial in-memory gateways -----------------------------------------

    /** The ledger: two rows on the seeded Wallets, unfiltered by the fakes
     * (the chrome tests exercise the screen, not the server-side filter
     * matching). */
    @Test
    fun `the toolbar stays pinned above the records while the list scrolls`() {
        // ADR-0005 (ticket #44): the search row and the Filters toggle are
        // fixed chrome under the header; only the records scroll. Deep in
        // the ledger the toolbar must still be on screen.
        transactions.rows = (1..40).map { index ->
            TransactionDto(
                id = index,
                type = TransactionType.EXPENSE,
                amount = "1.00",
                date = "2026-08-01",
                wallet_id = 1,
                description = "Scrolled row ${index.toString().padStart(2, '0')}",
                created_at = "2026-08-01T10:00:00Z",
            )
        }
        launchScreen()
        waitForLedger()

        repeat(8) {
            composeRule.onNodeWithTag("tx-list").performTouchInput { swipeUp() }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Scrolled row 40").fetchSemanticsNodes().isNotEmpty()
        }
        // The first record left the composition; the toolbar did not.
        assertTrue(composeRule.onAllNodesWithText("Scrolled row 01").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithTag("tx-search").assertIsDisplayed()
        composeRule.onNodeWithText("Filters ▸").assertIsDisplayed()
    }

    private class FakeTransactionGateway : TransactionGateway {
        var rows: List<TransactionDto> = defaultRows

        override suspend fun fetchPage(
            filters: TransactionFilters,
            cursor: String?,
            limit: Int,
        ): TransactionPageDto = TransactionPageDto(items = rows, next_cursor = null)

        override suspend fun createTransaction(draft: TransactionDraft): TransactionDto =
            error("unused")
        override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
            error("unused")
        override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto =
            error("unused")
        override suspend fun export(filters: TransactionFilters): ExportFile = error("unused")
    }

    private class FakeWalletGateway : WalletGateway {
        override suspend fun fetchWallets(): List<WalletDto> = listOf(cash, card)
        override suspend fun createWallet(name: String, type: WalletType, openingBalance: String): WalletDto =
            error("unused")
        override suspend fun renameWallet(id: Int, name: String): WalletDto = error("unused")
        override suspend fun freezeWallet(id: Int) = error("unused")
        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused")
    }

    private class FakeCategoryGateway : CategoryGateway {
        override suspend fun fetchCategories(): List<CategoryDto> = listOf(food)
        override suspend fun createCategory(name: String, type: CategoryType, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto = error("unused")
        override suspend fun deleteCategory(id: Int) = error("unused")
    }

    private class FakeRecurringCostGateway : RecurringCostGateway {
        override suspend fun fetchRecurringCosts(): List<RecurringCostDto> = listOf(rent)
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
        override suspend fun preview(fileName: String, content: ByteArray): ImportPreviewDto =
            error("unused")
        override suspend fun validateRow(
            row: ImportRowInput,
            earlierRows: List<ImportRowInput>,
        ): ImportRowValidationDto = error("unused")
        override suspend fun revalidateRows(
            rows: List<ImportRowInput>,
            targets: List<Int>,
        ): List<ImportRowRevalidationDto> = error("unused")
        override suspend fun confirm(rows: List<ImportRowInput>) = error("unused")
    }

    companion object {
        private val cash = WalletDto(1, "Cash", WalletType.CASH, "0.00", false, "2026-08-01T10:00:00Z")
        private val card = WalletDto(2, "Card", WalletType.CREDIT_CARD, "0.00", false, "2026-08-01T10:00:00Z")
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

        private val defaultRows = listOf(
            TransactionDto(
                id = 1,
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                wallet_id = 1,
                description = "Coffee",
                created_at = "2026-08-01T10:00:00Z",
            ),
            TransactionDto(
                id = 2,
                type = TransactionType.EXPENSE,
                amount = "6.00",
                date = "2026-08-02",
                wallet_id = 2,
                description = "Lunch",
                created_at = "2026-08-02T10:00:00Z",
            ),
        )
    }
}

/** The device GPS for the chrome tests: permission already held, no
 * position — no system prompt can ever be raised. */
private class ChromeSilentLocation : DeviceLocation {
    override fun permissionGranted(): Boolean = true

    override suspend fun currentPosition(): LatLng? = null
}
