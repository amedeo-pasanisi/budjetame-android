package com.budjetame.android.ui.shell

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.api.AuthConfigDto
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.auth.AuthGateway
import com.budjetame.android.ui.categories.CategoriesScreen
import com.budjetame.android.ui.categories.CategoryMergeOffer
import com.budjetame.android.ui.categories.CategoryModal
import com.budjetame.android.ui.categories.CategoryModalState
import com.budjetame.android.ui.common.ProvideAccountLocale
import com.budjetame.android.ui.imports.ImportRowEditor
import com.budjetame.android.ui.login.LoginScreen
import com.budjetame.android.ui.recurring.RecurringScreen
import com.budjetame.android.ui.recurringcosts.RecurringCostModalState
import com.budjetame.android.ui.recurringcosts.RecurringCostsModal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screens #63 translated follow the Account's Locale (ADR-0010): under
 * `it` the Recurring tab, Categories, the Import row editor and the auth
 * door render Italian — modals and their windows included — and the same
 * composables still read English under `en` (the existing suites).
 */
@RunWith(AndroidJUnit4::class)
class ScreensLocaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun the_recurring_tab_renders_italian() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                RecurringScreen(
                    recurringCosts = TabPagerTest.EmptyRecurringCostGateway(),
                    recurringIncomes = TabPagerTest.EmptyRecurringIncomeGateway(),
                )
            }
        }

        composeRule.onNodeWithText("Uscite").assertExists()
        composeRule.onNodeWithText("Entrate").assertExists()
        composeRule.onNodeWithText("Uscite Ricorrenti").assertExists()
        waitForText("Ancora nessuna uscita ricorrente. Aggiungine una per tenere traccia delle scadenze.")

        composeRule.onNodeWithText("Entrate").performClick()
        composeRule.onNodeWithText("Nuova entrata ricorrente").assertExists()
    }

    @Test
    fun the_recurring_modal_and_its_unit_menu_render_italian() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                RecurringCostsModal(
                    modal = RecurringCostModalState(intervalValue = "2", intervalUnit = IntervalUnit.WEEKS),
                    onNameChange = {},
                    onAmountChange = {},
                    onIntervalValueChange = {},
                    onIntervalUnitChange = {},
                    onStartDateChange = {},
                    onToggleOccurrence = {},
                    onSubmit = {},
                    onFreeze = {},
                    onUnfreeze = {},
                    onClose = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Nuova uscita ricorrente")[0].assertExists()
        composeRule.onNodeWithText("Si ripete ogni").assertExists()
        composeRule.onNodeWithText("settimane").assertExists()
        composeRule.onNodeWithText("La prima occorrenza. Lascia vuoto per iniziare da oggi.").assertExists()
        composeRule.onNodeWithText("Crea uscita ricorrente").assertExists()
        composeRule.onNodeWithText("Annulla").assertExists()
    }

    @Test
    fun categories_and_the_category_modal_render_italian() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                CategoriesScreen(categories = TabPagerTest.EmptyCategoryGateway())
            }
        }

        composeRule.onNodeWithText("Categorie").assertExists()
        waitForText("Ancora nessuna categoria. Aggiungine una per iniziare a raggruppare le transazioni.")

        composeRule.onNodeWithText("Nuova categoria").performClick()
        // The modal is its own window: it re-applies the Account Locale.
        waitForText("Crea categoria")
        composeRule.onNodeWithText("Colore").assertExists()
        composeRule.onNodeWithText("Icona (facoltativa)").assertExists()
    }

    @Test
    fun the_merge_offer_renders_italian() {
        val groceries = CategoryDto(1, "Spesa", CategoryType.EXPENSE, color = "#ef4444", created_at = "2026-08-01T10:00:00Z")
        composeRule.setContent {
            ProvideAccountLocale("it") {
                CategoryModal(
                    modal = CategoryModalState(
                        category = groceries,
                        name = "Alimentari",
                        mergeOffer = CategoryMergeOffer(targetId = 2, transactionCount = 3),
                    ),
                    onNameChange = {},
                    onTypeChange = {},
                    onIconChange = {},
                    onColorChange = {},
                    onSubmit = {},
                    onMerge = {},
                    onCancelMerge = {},
                    onDelete = {},
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Unire Spesa in Alimentari? 3 transazioni verranno spostate — questa operazione non può essere annullata.",
        ).assertExists()
        composeRule.onNodeWithText("Annulla unione").assertExists()
        composeRule.onNodeWithContentDescription("Usa colore #ef4444").assertExists()
    }

    @Test
    fun the_import_row_editor_renders_italian() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                ImportRowEditor(
                    row = ImportRowDto(row = 3, status = ImportRowStatus.ERROR),
                    wallets = emptyList(),
                    categories = emptyList(),
                    saving = false,
                    error = null,
                    fieldErrors = emptyMap(),
                    onSave = {},
                    onClose = {},
                    onAddWallet = { _, _ -> },
                    walletToSelect = null,
                    onAddCategory = { _, _ -> },
                    categoryToSelect = null,
                )
            }
        }

        composeRule.onNodeWithText("Modifica riga 3").assertExists()
        composeRule.onNodeWithText("Latitudine").assertExists()
        composeRule.onNodeWithText("Salva").assertExists()
    }

    @Test
    fun the_auth_door_renders_italian() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                LoginScreen(auth = NoGoogleAuthGateway(), onSignedIn = {})
            }
        }

        composeRule.onNodeWithText("Accedi per vedere i tuoi soldi.").assertExists()
        composeRule.onNodeWithText("Password dimenticata?").assertExists()
        composeRule.onNodeWithText("Resetta").performClick()
        composeRule.onNodeWithText("Ti invieremo un link via email per reimpostare la password.").assertExists()
        composeRule.onNodeWithText("Invia link di reset").assertExists()
    }
}

/** An auth door without the Google button; nothing is ever submitted. */
private class NoGoogleAuthGateway : AuthGateway {
    override suspend fun signIn(email: String, password: String): AccountDto = error("unused")
    override suspend fun signUp(email: String, password: String): AccountDto = error("unused")
    override suspend fun signInWithGoogle(idToken: String): AccountDto = error("unused")
    override suspend fun authConfig(): AuthConfigDto = AuthConfigDto(google_client_id = "")
    override suspend fun requestPasswordReset(email: String) = error("unused")
}
