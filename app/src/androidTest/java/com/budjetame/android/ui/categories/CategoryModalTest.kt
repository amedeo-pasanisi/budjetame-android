package com.budjetame.android.ui.categories

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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.R
import com.budjetame.android.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Category modal's submit-and-validate (ADR-0009, ticket #54): Save is
 * enabled with an empty Name, an invalid Save renders "Enter a name" under
 * the Name field, and a server rejection stays in the banner. The modal is
 * driven with a local state harness running the shared validator, as each
 * host's ViewModel does; the hosts themselves are covered by the seam tests.
 */
@RunWith(AndroidJUnit4::class)
class CategoryModalTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val inError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)

    private var submitted = 0

    private fun setModal(initial: CategoryModalState) {
        composeRule.setContent {
            var modal by remember { mutableStateOf(initial) }
            CategoryModal(
                modal = modal,
                onNameChange = { modal = modal.copy(name = it) },
                onTypeChange = { modal = modal.copy(type = it) },
                onIconChange = { modal = modal.copy(icon = it) },
                onColorChange = { modal = modal.copy(color = it) },
                onSubmit = {
                    val errors = validateCategoryForm(modal)
                    modal = modal.copy(fieldErrors = errors)
                    if (errors.isEmpty()) submitted++
                },
                onMerge = {},
                onCancelMerge = {},
                onDelete = {},
                onClose = {},
            )
        }
    }

    @Test
    fun an_empty_name_saves_into_the_inline_error_and_submits_nothing() {
        setModal(CategoryModalState())

        composeRule.onNodeWithText("Create category").assertIsEnabled()
        composeRule.onNodeWithTag("category-name").assert(!inError)
        composeRule.onNodeWithText("Create category").performClick()

        composeRule.onNodeWithTag("category-name")
            .assert(inError)
            .assert(hasText("Enter a name"))
        assertEquals(0, submitted)

        // Typing keeps the error; only the next Save clears it.
        composeRule.onNodeWithTag("category-name").performTextInput("Groceries")
        composeRule.onNodeWithTag("category-name").assert(hasText("Enter a name"))
        composeRule.onNodeWithText("Create category").performClick()
        composeRule.onNodeWithTag("category-name").assert(!inError)
        assertEquals(1, submitted)
    }

    @Test
    fun save_is_disabled_only_while_submitting() {
        setModal(CategoryModalState(name = "Groceries", submitting = true))

        composeRule.onNodeWithText("Saving…").assertIsNotEnabled()
    }

    @Test
    fun a_server_rejection_stays_in_the_banner_not_under_the_name() {
        setModal(CategoryModalState(name = "Taken", error = UiText.of(R.string.errors_categories_conflict)))

        composeRule.onNodeWithText("A category with this name already exists.").assertIsDisplayed()
        composeRule.onNodeWithTag("category-name").assert(!inError)
    }
}
