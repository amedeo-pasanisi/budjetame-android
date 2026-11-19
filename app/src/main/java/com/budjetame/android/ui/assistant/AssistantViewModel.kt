package com.budjetame.android.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.assistant.AssistantGateway
import com.budjetame.android.data.assistant.AssistantSettingsGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The Assistant (ticket #68, web ADR-0035 / issue #132): its button's
 * presence and the open conversation — a thin chat screen over the
 * stateless `POST /assistant/turns`.
 *
 * The button shows only while the Account has Assistant Settings: the status
 * is read at start and again by [refreshAvailability] whenever the Settings
 * dialog closes (where they are saved or removed), and a turn answered 409
 * (removed on another device) hides it and closes the Assistant.
 *
 * The transcript the server needs — every message as last received, tool
 * calls and results included — is held here opaquely and carried back
 * untouched; [UiState.lines] are only the user's sentences and the written
 * replies. Nothing is stored: [close] forgets the conversation, so a new
 * one cannot touch the old one's Drafts (the server derives them from the
 * transcript). A successful turn is a write through the transport, which
 * bumps DataVersion (ADR-0002): the Transactions tab reloads its Drafts.
 *
 * The provider's failures and the server's other bounds come back as 200
 * replies and show as ordinary lines. Only the statuses that are not
 * replies get a line of the client's: the hourly bound (429) as an
 * ordinary Assistant line with the wait the server names, anything else as
 * a failure line. A failed turn is not in the transcript, and its sentence
 * goes back into the box for a retry.
 *
 * Every turn goes through [sendSentence], and the box changes only through
 * [onTextChange]: the two places a Dictation (ticket #69) hooks into — its
 * end sends the box's text followed by what was heard, and typing cancels
 * it.
 */
class AssistantViewModel(
    private val turns: AssistantGateway,
    private val settings: AssistantSettingsGateway,
) : ViewModel() {

    enum class Speaker { USER, ASSISTANT }

    /** One line of the conversation as shown. A failure line is the
     * client's own words for a turn that did not happen, styled apart. */
    data class Line(val speaker: Speaker, val text: String, val failure: Boolean = false)

    data class UiState(
        /** The Assistant Settings status has been read at least once:
         * until then [available] is only the default, not an answer. */
        val availabilityKnown: Boolean = false,
        /** The Account has Assistant Settings: the button shows. */
        val available: Boolean = false,
        val open: Boolean = false,
        val lines: List<Line> = emptyList(),
        /** The text box. */
        val text: String = "",
        /** A turn is in flight: Send waits for its reply. */
        val pending: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var availabilityJob: Job? = null
    private var turnJob: Job? = null

    /** The conversation as last received: the previous turns' tails joined. */
    private var transcript: List<JsonObject> = emptyList()

    init {
        refreshAvailability()
    }

    /** Re-read whether the Account has Assistant Settings; an unreadable
     * status hides the button, as on the web. */
    fun refreshAvailability() {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            val configured = try {
                settings.fetchStatus().configured
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            _uiState.update { it.copy(availabilityKnown = true, available = configured) }
        }
    }

    fun open() {
        _uiState.update { it.copy(open = true) }
    }

    /** Close the Assistant and forget the conversation: the transcript,
     * the lines, the box and any turn in flight (its reply, if the server
     * still writes one, is dropped; its Drafts show on the Transactions
     * tab all the same). */
    fun close() {
        turnJob?.cancel()
        transcript = emptyList()
        _uiState.update { it.copy(open = false, lines = emptyList(), text = "", pending = false) }
    }

    fun onTextChange(value: String) {
        _uiState.update { it.copy(text = value) }
    }

    /** Send what is typed in the box. */
    fun send() {
        sendSentence(_uiState.value.text.trim())
    }

    /** One turn: the sentence goes to the server with the transcript as last
     * received. Nothing is sent while a reply is pending. */
    private fun sendSentence(sentence: String) {
        if (sentence.isEmpty() || _uiState.value.pending) return
        val userLine = Line(Speaker.USER, sentence)
        _uiState.update { it.copy(lines = it.lines + userLine, text = "", pending = true) }
        turnJob = viewModelScope.launch {
            try {
                val result = turns.sendTurn(transcript, sentence)
                transcript = transcript + result.transcript_tail
                _uiState.update {
                    it.copy(lines = it.lines + Line(Speaker.ASSISTANT, result.reply), pending = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val apiError = error as? ApiException
                // The turn did not happen: the sentence leaves the
                // conversation and goes back into the box.
                val failureLine = when (apiError?.status) {
                    409 -> {
                        close()
                        _uiState.update { it.copy(available = false) }
                        return@launch
                    }
                    429 -> Line(Speaker.ASSISTANT, apiError.detail ?: HOURLY_BOUND)
                    else -> Line(Speaker.ASSISTANT, COULD_NOT_ANSWER, failure = true)
                }
                _uiState.update {
                    it.copy(
                        lines = it.lines.filterNot { line -> line === userLine } + failureLine,
                        text = sentence,
                        pending = false,
                    )
                }
            }
        }
    }

    private companion object {
        const val HOURLY_BOUND = "You have reached the hourly limit of Assistant requests. Try again later."
        const val COULD_NOT_ANSWER = "The Assistant could not answer. Try again."
    }
}
