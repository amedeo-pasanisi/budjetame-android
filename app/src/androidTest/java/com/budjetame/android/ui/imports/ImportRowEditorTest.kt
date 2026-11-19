package com.budjetame.android.ui.imports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.ui.common.AmountInputMessages
import com.budjetame.android.ui.common.FieldErrors
import com.budjetame.android.ui.common.deviceText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Verification row editor's submit-and-validate (ADR-0009, ticket #56):
 * Save is enabled on an invalid row, and pressing it renders each Field
 * Error under its own field and sends nothing; a server rejection stays the
 * bottom banner. The editor is driven by a local harness whose Save runs
 * [validateImportRow] like the ViewModel does; the save path itself is
 * covered by the ImportViewModel seam tests.
 */
@RunWith(AndroidJUnit4::class)
class ImportRowEditorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val inError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
    private val checking = WalletDto(1, "Checking", WalletType.CHECKING, "100.00", false, "2026-08-01T10:00:00Z")

    private val submitted = mutableListOf<ImportRowInput>()

    private fun setEditor(row: ImportRowDto, serverError: String? = null) {
        composeRule.setContent {
            var fieldErrors by remember { mutableStateOf<FieldErrors>(emptyMap()) }
            ImportRowEditor(
                row = row,
                wallets = listOf(checking),
                categories = emptyList(),
                saving = false,
                error = serverError,
                fieldErrors = fieldErrors,
                onSave = { input ->
                    fieldErrors = validateImportRow(input)
                    if (fieldErrors.isEmpty()) submitted += input
                },
                onClose = {},
                onAddWallet = { _, _ -> },
                walletToSelect = null,
                onAddCategory = { _, _ -> },
                categoryToSelect = null,
            )
        }
    }

    @Test
    fun saving_an_incomplete_row_shows_each_field_error_and_sends_nothing() {
        setEditor(ImportRowDto(row = 4, status = ImportRowStatus.ERROR, error = "Missing amount"))

        composeRule.onNodeWithTag("im-amount").assert(!inError)
        composeRule.onNodeWithText("Save").assertIsEnabled().performClick()

        composeRule.onNodeWithTag("im-amount").assert(inError).assert(hasText(AmountInputMessages.EMPTY.deviceText()))
        composeRule.onNodeWithTag("im-date").assert(inError).assert(hasText("Choose a date"))
        composeRule.onNodeWithTag("im-wallet").assert(inError).assert(hasText("Choose a wallet."))
        assertEquals(0, submitted.size)
    }

    @Test
    fun errors_stay_while_typing_and_a_valid_save_clears_them() {
        setEditor(
            ImportRowDto(
                row = 4,
                status = ImportRowStatus.ERROR,
                type = TransactionType.EXPENSE,
                date = "2026-08-03",
                wallet = "Checking",
                error = "Missing amount",
            ),
        )

        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithTag("im-amount").assert(hasText(AmountInputMessages.EMPTY.deviceText()))

        // Typing never clears the error; only the next Save does.
        composeRule.onNodeWithTag("im-amount").performTextInput("7,5")
        composeRule.onNodeWithTag("im-amount").assert(inError)
        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithTag("im-amount").assert(!inError)
        assertEquals(listOf("7,5"), submitted.map { it.amount })
    }

    @Test
    fun a_transfer_with_one_wallet_on_both_legs_flags_both() {
        setEditor(
            ImportRowDto(
                row = 5,
                status = ImportRowStatus.ERROR,
                type = TransactionType.TRANSFER,
                date = "2026-08-03",
                amount = "10.00",
                source_wallet = "Checking",
                destination_wallet = "Checking",
                error = "Source and destination must differ",
            ),
        )

        composeRule.onNodeWithText("Save").performClick()

        val same = "Source and destination must be different wallets."
        composeRule.onNodeWithTag("im-source").assert(inError).assert(hasText(same))
        composeRule.onNodeWithTag("im-destination").assert(inError).assert(hasText(same))
        assertEquals(0, submitted.size)
    }

    @Test
    fun a_server_rejection_is_the_banner_not_a_field_error() {
        setEditor(
            ImportRowDto(
                row = 4,
                type = TransactionType.EXPENSE,
                date = "2026-08-03",
                amount = "7.00",
                wallet = "Checking",
            ),
            serverError = "boom",
        )

        composeRule.onNodeWithText("boom").assertIsDisplayed()
        composeRule.onNodeWithTag("im-amount").assert(!inError)
        composeRule.onNodeWithTag("im-wallet").assert(!inError)
    }
}
