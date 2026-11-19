package com.budjetame.android.ui.wallets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.R
import com.budjetame.android.ui.common.AmountInputMessages
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.ui.common.deviceText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Wallet modal's submit-and-validate (ADR-0009, ticket #55): Save is
 * enabled with an empty Name, an invalid Save renders each Field Error
 * under its own field (Name, Opening balance), and a server rejection stays
 * in the banner. The modal is driven with a local state harness running the
 * shared validator, as each host's ViewModel does; the hosts themselves are
 * covered by the seam tests.
 */
@RunWith(AndroidJUnit4::class)
class WalletModalTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val inError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    private var submitted = 0

    private fun setModal(initial: WalletModalState) {
        composeRule.setContent {
            var modal by remember { mutableStateOf(initial) }
            WalletModal(
                modal = modal,
                onNameChange = { modal = modal.copy(name = it) },
                onTypeChange = { modal = modal.copy(type = it) },
                onOpeningBalanceChange = { modal = modal.copy(openingBalance = it) },
                onSubmit = {
                    val errors = validateWalletForm(modal)
                    modal = modal.copy(fieldErrors = errors)
                    if (errors.isEmpty()) submitted++
                },
                onFreeze = {},
                onUnfreeze = {},
                onClose = {},
            )
        }
    }

    @Test
    fun an_invalid_save_renders_each_error_under_its_field_and_submits_nothing() {
        setModal(WalletModalState(openingBalance = "12abc"))

        composeRule.onNodeWithText("Create wallet").assertIsEnabled()
        composeRule.onNodeWithTag("wallet-name").assert(!inError)
        composeRule.onNodeWithText("Create wallet").performClick()

        composeRule.onNodeWithTag("wallet-name")
            .assert(inError)
            .assert(hasText("Enter a name"))
        composeRule.onNodeWithTag("wallet-opening-balance")
            .assert(inError)
            .assert(hasText(AmountInputMessages.INVALID.deviceText()))
        assertEquals(0, submitted)

        // Typing keeps the errors; only the next Save clears them.
        composeRule.onNodeWithTag("wallet-name").performTextInput("Savings")
        composeRule.onNodeWithTag("wallet-opening-balance").performTextClearance()
        composeRule.onNodeWithTag("wallet-opening-balance").performTextInput("17,5")
        composeRule.onNodeWithTag("wallet-name").assert(hasText("Enter a name"))
        composeRule.onNodeWithText("Create wallet").performClick()
        composeRule.onNodeWithTag("wallet-name").assert(!inError)
        composeRule.onNodeWithTag("wallet-opening-balance").assert(!inError)
        assertEquals(1, submitted)
    }

    @Test
    fun save_is_disabled_only_while_submitting() {
        setModal(WalletModalState(name = "Savings", submitting = true))

        composeRule.onNodeWithText("Saving…").assertIsNotEnabled()
    }

    @Test
    fun a_server_rejection_stays_in_the_banner_not_under_the_name() {
        setModal(WalletModalState(name = "Taken", error = UiText.of(R.string.errors_wallets_conflict)))

        composeRule.onNodeWithText("A wallet with this name already exists.").assertIsDisplayed()
        composeRule.onNodeWithTag("wallet-name").assert(!inError)
    }
}
