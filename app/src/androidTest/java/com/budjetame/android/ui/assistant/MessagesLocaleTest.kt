package com.budjetame.android.ui.assistant

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.R
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.ui.common.AmountInputMessages
import com.budjetame.android.ui.common.ProvideAccountLocale
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.ui.common.fieldErrorSupportingText
import com.budjetame.android.ui.common.resolve
import com.budjetame.android.ui.common.serverMessage
import com.budjetame.android.ui.theme.BudjetameTheme
import com.budjetame.android.ui.undo.DeletedEntity
import com.budjetame.android.ui.undo.UndoEntry
import com.budjetame.android.ui.undo.UndoState
import com.budjetame.android.ui.undo.UndoToastStack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #71 under an Italian Account (ADR-0010): the Assistant (its own
 * Dialog window), the Quick Dictation card, the Undo stack with a server
 * message through the error catalogue, and a Field Error all render
 * Italian, while an unknown server message passes through unchanged. The
 * English suites prove `en`.
 */
@RunWith(AndroidJUnit4::class)
class MessagesLocaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun the_assistant_dialog_speaks_the_account_locale() {
        val state = AssistantViewModel.UiState(
            availabilityKnown = true,
            available = true,
            open = true,
            lines = listOf(
                AssistantViewModel.Line(AssistantViewModel.Speaker.USER, UiText.Raw("ho speso 30 euro")),
                AssistantViewModel.Line(
                    AssistantViewModel.Speaker.ASSISTANT,
                    UiText.of(R.string.assistant_could_not_answer),
                    failure = true,
                ),
            ),
            dictationFailed = true,
        )
        composeRule.setContent {
            ProvideAccountLocale("it") {
                BudjetameTheme {
                    AssistantDialog(
                        state = state,
                        onTextChange = {},
                        onSend = {},
                        onStartDictation = {},
                        onStopDictation = {},
                        onClose = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Assistente").assertExists()
        composeRule.onNodeWithText("Invia").assertExists()
        composeRule.onNodeWithText("La dettatura non ha funzionato. Scrivi la frase.").assertExists()
        composeRule.onNodeWithText("L'Assistente non ha potuto rispondere. Riprova.").assertExists()
        // The user's own words are never translated.
        composeRule.onNodeWithText("ho speso 30 euro").assertExists()
    }

    @Test
    fun the_quick_dictation_card_speaks_the_account_locale() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                BudjetameTheme {
                    QuickDictationFrame(onClose = {}) {
                        QuickDictationCannotRun(AssistantViewModel.CannotRun.OFFLINE, onOpenApp = {})
                    }
                }
            }
        }

        composeRule.onNodeWithText("Registra Budjetame").assertExists()
        composeRule.onNodeWithText("Budjetame non è raggiungibile: controlla la connessione e riprova.").assertExists()
        composeRule.onNodeWithText("Apri Budjetame").assertExists()
    }

    @Test
    fun an_undo_entry_and_its_known_server_error_render_italian() {
        val row = TransactionDto(
            id = 1,
            type = TransactionType.EXPENSE,
            amount = "3.50",
            date = "2026-08-01",
            wallet_id = 1,
            description = "Caffè",
            created_at = "2026-08-01T10:00:00Z",
        )
        val entry = UndoEntry(
            deleted = DeletedEntity.Transaction(row),
            createdAtMillis = 0L,
            error = serverMessage("This Occurrence was already paid by another Transaction"),
        )
        composeRule.setContent {
            ProvideAccountLocale("it") {
                BudjetameTheme {
                    UndoToastStack(state = UndoState(listOf(entry), nowMillis = 0L), onUndo = {}, onDismiss = {})
                }
            }
        }

        composeRule.onNodeWithText("Transazione eliminata · Caffè").assertExists()
        composeRule.onNodeWithText("Annulla").assertExists()
        composeRule.onNodeWithText("Questa occorrenza è già stata pagata da un'altra transazione").assertExists()
    }

    @Test
    fun an_unknown_server_message_passes_through_unchanged() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                Text(serverMessage("Something the catalogue does not know").resolve())
            }
        }

        composeRule.onNodeWithText("Something the catalogue does not know").assertExists()
    }

    @Test
    fun a_field_error_renders_in_the_account_locale() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                BudjetameTheme {
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        isError = true,
                        supportingText = fieldErrorSupportingText(AmountInputMessages.EMPTY),
                    )
                }
            }
        }

        composeRule.onNodeWithText("Inserisci un importo", useUnmergedTree = true).assertExists()
    }
}
