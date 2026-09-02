package com.budjetame.android.ui.imports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowRevalidationDto
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.ImportRowValidationDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.transactions.ADD_CATEGORY_OPTION
import com.budjetame.android.ui.transactions.ADD_WALLET_OPTION
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ticket #27's flows through the composed Import screen: the Preview's
 * on-resume re-check (web issue #76) and the row editor's inline Wallet and
 * Category creation (ADR-0013/0014, web issue #78). The screen is driven
 * directly — the ViewModel with trivial in-memory gateways, the draft
 * moved through the pick and preview phases by the test (the system file
 * picker itself is not drivable) — and the fake import resource re-validates
 * against the fakes' Wallets and Categories, so a creation flips the rows
 * that waited on it exactly like the backend would.
 */
@RunWith(AndroidJUnit4::class)
class ImportRevalidationTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** True while the Import screen is composed — the test flips it to
     * simulate a tab switch (the Draft itself lives on in the ViewModel). */
    private val screenVisible = mutableStateOf(true)

    /** The canned preview both inline-creation tests open: row 2 Ready,
     * row 3 a Problem naming a Wallet that does not exist yet. */
    private fun walletProblemPreview(): ImportPreviewDto = ImportPreviewDto(
        rows = listOf(
            ImportRowDto(
                row = 2,
                status = ImportRowStatus.OK,
                type = TransactionType.EXPENSE,
                date = "2026-08-01",
                amount = "12.50",
                wallet = "Checking",
            ),
            ImportRowDto(
                row = 3,
                status = ImportRowStatus.ERROR,
                type = TransactionType.EXPENSE,
                date = "2026-08-02",
                amount = "5.00",
                wallet = "Mystery",
                error = "Unknown wallet 'Mystery'",
            ),
        ),
        ok_count = 1,
        error_count = 1,
        duplicate_count = 0,
    )

    private fun launchScreen(
        preview: ImportPreviewDto,
        viewModel: ImportViewModel,
        walletGateway: FakeWalletGateway,
        categoryGateway: FakeCategoryGateway,
    ) {
        composeRule.setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val draft = state.draft
            if (screenVisible.value && draft != null) {
                ImportScreen(
                    draft = draft,
                    wallets = walletGateway.wallets,
                    categories = categoryGateway.categories,
                    viewModel = viewModel,
                )
            }
        }
    }

    /** The draft is driven as far as the Preview phase. */
    private fun ImportViewModel.openPreview() {
        open()
        onFilePicked("rows.csv", "irrelevant".toByteArray())
        readFile()
    }

    /** Simulate a tab switch: the Import screen leaves and re-enters
     * composition while its ViewModel — and with it the Draft — stays
     * alive. */
    private fun switchTabAwayAndBack() {
        screenVisible.value = false
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isEmpty()
        }
        screenVisible.value = true
    }

    private fun waitForPreview() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("1 ready · 0 duplicates · 1 problem")
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForCounts(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun returning_to_the_preview_rechecks_problem_rows_and_flips_the_rows_that_now_pass() {
        val walletGateway = FakeWalletGateway()
        val categoryGateway = FakeCategoryGateway()
        val imports = FakeImportGateway(walletGateway, categoryGateway, walletProblemPreview())
        val viewModel = ImportViewModel(imports, walletGateway, categoryGateway)
        launchScreen(walletProblemPreview(), viewModel, walletGateway, categoryGateway)

        // The flow opens in the pick phase (no re-check) and reads the
        // preview: row 3 is a Problem.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Choose file").fetchSemanticsNodes().isNotEmpty()
        }
        viewModel.openPreview()
        waitForPreview()
        assertEquals(0, imports.revalidateCalls)

        // "Away from the tab" the missing Wallet is created elsewhere. The
        // Preview resumes — the screen re-enters composition — and the
        // problem row is re-checked in one batch and flips to Ready.
        walletGateway.wallets.add(
            WalletDto(2, "Mystery", WalletType.CHECKING, "0.00", false, "2026-08-01T10:00:00Z"),
        )
        switchTabAwayAndBack()

        waitForCounts("2 ready · 0 duplicates · 0 problems")
        assertEquals(1, imports.revalidateCalls)
    }

    @Test
    fun a_wallet_created_from_the_row_editor_is_real_at_once_and_flips_the_waiting_row() {
        val walletGateway = FakeWalletGateway()
        val categoryGateway = FakeCategoryGateway()
        val imports = FakeImportGateway(walletGateway, categoryGateway, walletProblemPreview())
        val viewModel = ImportViewModel(imports, walletGateway, categoryGateway)
        launchScreen(walletProblemPreview(), viewModel, walletGateway, categoryGateway)
        viewModel.openPreview()
        waitForPreview()

        // Open the row editor for the Problem row and pick the Wallet
        // select's "New wallet…" sentinel.
        viewModel.openEditor(3)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Edit row 3").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("im-wallet").performClick()
        composeRule.onNodeWithText(ADD_WALLET_OPTION).performClick()

        // The create form is stacked on the row editor, prefilled with the
        // file's missing name and locked to the non-Contact types; the
        // editor's draft survives below it.
        composeRule.onNodeWithText("New wallet").assertIsDisplayed()
        composeRule.onNodeWithText("Edit row 3").assertIsDisplayed()
        composeRule.onNodeWithText("Checking, Credit Card, Cash · fixed for this form")
            .assertIsDisplayed()

        // Confirming creates the Wallet for real (the shared endpoint),
        // auto-selects it into the editor, and re-validates the rows that
        // waited on it — row 3 flips to Ready in the list behind the
        // editor.
        composeRule.onNodeWithText("Create wallet").performClick()
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.draft?.preview?.rows?.first { it.row == 3 }?.status ==
                ImportRowStatus.OK
        }
        assertEquals("Mystery", walletGateway.createdName)
        assertEquals(1, imports.revalidateCalls)

        // Closing the editor shows the flipped list.
        composeRule.onNodeWithText("Cancel").performClick()
        waitForCounts("2 ready · 0 duplicates · 0 problems")
    }

    @Test
    fun a_category_created_from_the_row_editor_locks_to_the_row_type_and_flips_the_waiting_row() {
        val walletGateway = FakeWalletGateway()
        val categoryGateway = FakeCategoryGateway()
        val imports = FakeImportGateway(walletGateway, categoryGateway, categoryProblemPreview())
        val viewModel = ImportViewModel(imports, walletGateway, categoryGateway)
        launchScreen(categoryProblemPreview(), viewModel, walletGateway, categoryGateway)
        viewModel.openPreview()
        waitForPreview()

        // The Problem row names a Category that does not exist yet; its
        // editor's Category select carries the "New category…" sentinel.
        viewModel.openEditor(3)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Edit row 3").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("im-category").performClick()
        composeRule.onNodeWithText(ADD_CATEGORY_OPTION).performClick()

        // The create form is stacked on the row editor, prefilled with the
        // file's missing name and locked to the row's type.
        composeRule.onNodeWithText("New category").assertIsDisplayed()
        composeRule.onNodeWithText("Expense · fixed for this form").assertIsDisplayed()

        composeRule.onNodeWithText("Create category").performClick()
        composeRule.waitUntil(5_000) {
            viewModel.uiState.value.draft?.preview?.rows?.first { it.row == 3 }?.status ==
                ImportRowStatus.OK
        }
        assertEquals("Groceries", categoryGateway.createdName)
        assertEquals(CategoryType.EXPENSE, categoryGateway.createdType)
        assertEquals(1, imports.revalidateCalls)
    }

    /** The canned preview for the Category flow: row 3's Problem is its
     * missing Category, not its Wallet. */
    private fun categoryProblemPreview(): ImportPreviewDto = ImportPreviewDto(
        rows = listOf(
            ImportRowDto(
                row = 2,
                status = ImportRowStatus.OK,
                type = TransactionType.EXPENSE,
                date = "2026-08-01",
                amount = "12.50",
                wallet = "Checking",
            ),
            ImportRowDto(
                row = 3,
                status = ImportRowStatus.ERROR,
                type = TransactionType.EXPENSE,
                date = "2026-08-02",
                amount = "5.00",
                wallet = "Checking",
                category = "Groceries",
                error = "Unknown expense category 'Groceries'",
            ),
        ),
        ok_count = 1,
        error_count = 1,
        duplicate_count = 0,
    )

    /** The Wallets the Import screen's editor and the fake import resource
     * read: "Checking" exists from the start; a created Wallet joins the
     * list immediately (ADR-0014). */
    private class FakeWalletGateway : WalletGateway {
        val wallets = mutableListOf(
            WalletDto(1, "Checking", WalletType.CHECKING, "100.00", false, "2026-08-01T10:00:00Z"),
        )
        var createdName: String? = null
            private set

        override suspend fun fetchWallets(): List<WalletDto> = wallets

        override suspend fun createWallet(
            name: String,
            type: WalletType,
            openingBalance: String,
        ): WalletDto {
            val created = WalletDto(wallets.size + 1, name, type, openingBalance, false, "2026-08-01T10:00:00Z")
            createdName = name
            wallets.add(created)
            return created
        }

        override suspend fun renameWallet(id: Int, name: String): WalletDto = error("unused")
        override suspend fun freezeWallet(id: Int) = error("unused")
        override suspend fun unfreezeWallet(id: Int): WalletDto = error("unused")
    }

    /** The Categories the editor and the fake import resource read. */
    private class FakeCategoryGateway : CategoryGateway {
        val categories = mutableListOf<CategoryDto>()
        var createdName: String? = null
            private set
        var createdType: CategoryType? = null
            private set

        override suspend fun fetchCategories(): List<CategoryDto> = categories

        override suspend fun createCategory(
            name: String,
            type: CategoryType,
            icon: String,
            color: String,
        ): CategoryDto {
            val created = CategoryDto(
                categories.size + 1,
                name,
                type,
                icon.ifBlank { null },
                color,
                "2026-08-01T10:00:00Z",
            )
            createdName = name
            createdType = type
            categories.add(created)
            return created
        }

        override suspend fun updateCategory(id: Int, name: String, icon: String, color: String): CategoryDto =
            error("unused")
        override suspend fun mergeCategory(id: Int, targetId: Int): CategoryDto = error("unused")
        override suspend fun deleteCategory(id: Int) = error("unused")
    }

    /** The import resource's fake: a canned preview plus a re-validation
     * that resolves rows against the fakes' Wallets and Categories — a row
     * whose names all resolve comes back Ready, a row still naming a
     * missing entity stays a Problem with its message. */
    private class FakeImportGateway(
        private val wallets: FakeWalletGateway,
        private val categories: FakeCategoryGateway,
        private val preview: ImportPreviewDto,
    ) : ImportGateway {
        var revalidateCalls = 0
            private set

        override suspend fun preview(fileName: String, content: ByteArray): ImportPreviewDto = preview

        override suspend fun validateRow(
            row: ImportRowInput,
            earlierRows: List<ImportRowInput>,
        ): ImportRowValidationDto = verdict(row)

        override suspend fun revalidateRows(
            rows: List<ImportRowInput>,
            targets: List<Int>,
        ): List<ImportRowRevalidationDto> {
            revalidateCalls++
            return targets.map { rowNumber ->
                val input = rows.first { it.row == rowNumber }
                val result = verdict(input)
                ImportRowRevalidationDto(rowNumber, result.status, result.error)
            }
        }

        override suspend fun confirm(rows: List<ImportRowInput>): List<TransactionDto> = error("unused")

        private fun verdict(row: ImportRowInput): ImportRowValidationDto {
            val walletName = row.wallet ?: row.source_wallet ?: row.destination_wallet
            val walletKnown = walletName != null &&
                wallets.wallets.any { it.name.equals(walletName, ignoreCase = true) }
            if (!walletKnown) {
                return ImportRowValidationDto(ImportRowStatus.ERROR, error = "Unknown wallet '$walletName'")
            }
            if (row.type != TransactionType.TRANSFER && row.category != null) {
                val type = if (row.type == TransactionType.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
                val known = categories.categories.any {
                    it.type == type && it.name.equals(row.category, ignoreCase = true)
                }
                if (!known) {
                    val kind = if (row.type == TransactionType.INCOME) "income" else "expense"
                    return ImportRowValidationDto(
                        ImportRowStatus.ERROR,
                        error = "Unknown $kind category '${row.category}'",
                    )
                }
            }
            return ImportRowValidationDto(ImportRowStatus.OK)
        }
    }
}
