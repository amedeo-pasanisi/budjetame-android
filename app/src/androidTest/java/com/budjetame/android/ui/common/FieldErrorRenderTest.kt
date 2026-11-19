package com.budjetame.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.ui.theme.BudjetameTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Field Error render convention (ADR-0009, ticket #50), proven once on
 * a minimal submit-and-validate host form — one Amount field and a Save
 * button wired exactly the way every entity form wires it: Save is always
 * enabled, a failed Save stores the validator's Field Errors and submits
 * nothing, the field renders its message through `isError` +
 * [fieldErrorSupportingText], and only the next Save refreshes the errors.
 */
@RunWith(AndroidJUnit4::class)
class FieldErrorRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val submitted = mutableListOf<String>()

    /** The host form: the convention in KDoc of FieldError.kt, verbatim. */
    @Composable
    private fun AmountForm() {
        var amount by remember { mutableStateOf("") }
        var fieldErrors by remember { mutableStateOf<FieldErrors>(emptyMap()) }
        Column {
            val error = fieldErrors[AMOUNT]
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                isError = error != null,
                supportingText = fieldErrorSupportingText(error),
                modifier = Modifier.testTag("amount"),
            )
            Button(
                onClick = {
                    fieldErrors = buildMap { amountFieldError(amount)?.let { put(AMOUNT, it) } }
                    if (fieldErrors.isEmpty()) {
                        submitted += parseAmountInput(amount)!!.toPlainString()
                    }
                },
            ) { Text("Save") }
        }
    }

    private fun setForm() = composeRule.setContent { BudjetameTheme { AmountForm() } }

    private val amountInError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    @Test
    fun a_valid_form_shows_no_Field_Error() {
        setForm()

        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithTag("amount").assert(!amountInError)
        composeRule.onNodeWithText(AmountInputMessages.EMPTY).assertDoesNotExist()
    }

    @Test
    fun a_failed_Save_reveals_the_Field_Error_under_its_field_and_submits_nothing() {
        setForm()

        composeRule.onNodeWithText("Save").performClick()

        // The field is marked in error, and its merged semantics carry the
        // message: TalkBack reads it as part of the Amount field.
        composeRule.onNodeWithTag("amount").assert(amountInError)
        composeRule.onNode(hasSetTextAction() and hasText(AmountInputMessages.EMPTY)).assertExists()
        // The message sits beneath the field's input line.
        val label = composeRule.onNodeWithText("Amount", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val message = composeRule.onNodeWithText(AmountInputMessages.EMPTY, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("the Field Error renders below the field", message.top > label.bottom)
        assertEquals(emptyList<String>(), submitted)
    }

    @Test
    fun a_Field_Error_stays_while_typing_and_the_next_Save_refreshes_it() {
        setForm()
        composeRule.onNodeWithTag("amount").performTextInput("abc")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText(AmountInputMessages.INVALID, useUnmergedTree = true).assertExists()

        // Fixing the field does not clear the error: only Save does.
        composeRule.onNodeWithTag("amount").performTextClearance()
        composeRule.onNodeWithTag("amount").performTextInput("17,5")
        composeRule.onNodeWithText(AmountInputMessages.INVALID, useUnmergedTree = true).assertExists()

        composeRule.onNodeWithText("Save").performClick()

        composeRule.onNodeWithText(AmountInputMessages.INVALID, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("amount").assert(!amountInError)
        assertEquals(listOf("17.5"), submitted)
    }

    private companion object {
        const val AMOUNT = "amount"
    }
}
