package com.budjetame.android.ui.validation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Field Error render pattern (ADR-0009, the umbrella's Seam 2): the
 * shared layer's one instrumentation suite. It proves the convention every
 * entity form will use — Material 3 `OutlinedTextField` with `isError` and
 * `supportingText`, the message rendered inline beneath its field — plus
 * the a11y contract: the field carries M3's `error` semantics while its
 * error is set, so a screen reader announces the message with the field.
 * The per-form suites drive their own real modals; this suite pins the
 * pattern itself.
 */
@RunWith(AndroidJUnit4::class)
class FieldErrorRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun an_invalid_field_renders_its_message_beneath_it_in_error_state() {
        composeRule.setContent {
            var errors by remember { mutableStateOf(fieldErrors(FieldKey.AMOUNT to Messages.AMOUNT_EMPTY)) }
            OutlinedTextField(
                value = "",
                onValueChange = {},
                label = { Text("Amount (€)") },
                isError = errors[FieldKey.AMOUNT] != null,
                supportingText = { FieldErrorText(errors[FieldKey.AMOUNT]) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("amount-field"),
            )
        }

        // The message renders inline, beneath the field.
        composeRule.onNodeWithText(Messages.AMOUNT_EMPTY).assertIsDisplayed()
        // The field is in the error state, and M3's merged semantics carry
        // the error with the field for TalkBack.
        composeRule.onNodeWithTag("amount-field")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Error"))
    }

    @Test
    fun a_valid_field_shows_no_error_text_and_no_error_semantics() {
        composeRule.setContent {
            OutlinedTextField(
                value = "",
                onValueChange = {},
                label = { Text("Amount (€)") },
                isError = false,
                supportingText = { FieldErrorText(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("amount-field"),
            )
        }

        composeRule.onNodeWithText(Messages.AMOUNT_EMPTY).assertDoesNotExist()
        composeRule.onNodeWithTag("amount-field")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun an_error_belongs_to_its_field_alone() {
        composeRule.setContent {
            val errors = fieldErrors(FieldKey.NAME to Messages.NAME_EMPTY)
            Column {
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text("Name") },
                    isError = errors[FieldKey.NAME] != null,
                    supportingText = { FieldErrorText(errors[FieldKey.NAME]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("name-field"),
                )
                OutlinedTextField(
                    value = "",
                    onValueChange = {},
                    label = { Text("Amount (€)") },
                    isError = errors[FieldKey.AMOUNT] != null,
                    supportingText = { FieldErrorText(errors[FieldKey.AMOUNT]) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("amount-field"),
                )
            }
        }

        // The Name field's error renders beneath the Name field...
        composeRule.onNodeWithText(Messages.NAME_EMPTY).assertIsDisplayed()
        // ...and the Amount field — a different field — stays clean.
        composeRule.onNodeWithTag("amount-field")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }
}