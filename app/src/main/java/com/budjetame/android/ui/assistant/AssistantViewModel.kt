package com.budjetame.android.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.assistant.AssistantGateway
import com.budjetame.android.data.assistant.AssistantSettingsGateway
import com.budjetame.android.data.dictation.DictationRecognizer
import com.budjetame.android.data.dictation.DictationTones
import com.budjetame.android.data.dictation.recognitionLanguage
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
 * Dictation (ticket #69; CONTEXT.md: Dictation) listens through the
 * [DictationRecognizer] seam in the Account's Locale. The screen starts one
 * when the Assistant opens and on a microphone tap, once it holds the
 * microphone permission; [startDictation] starts none while a reply is
 * pending. The start tone sounds when the recognizer is really listening,
 * the end tone at its end — so a refused microphone sounds nothing. What
 * was heard goes through [sendSentence] after the box's text; typing
 * ([onTextChange]), sending and closing drop it, silently and unsent. Only
 * a failure the user did not choose raises [UiState.dictationFailed].
 */
class AssistantViewModel(
    private val turns: AssistantGateway,
    private val settings: AssistantSettingsGateway,
    /** The device's speech recognizer; null where it has none: no
     * microphone, the Assistant is the box alone. */
    private val recognizer: DictationRecognizer?,
    private val tones: DictationTones,
    /** The Account's Locale as the server serves it: the language a
     * Dictation listens in. */
    private val locale: String,
    /** Quick Dictation (ticket #65): the Assistant opened from outside the
     * app as a voice-only card, open from the start. */
    private val quick: Boolean = false,
) : ViewModel() {

    enum class Speaker { USER, ASSISTANT }

    /** Why a Quick Dictation card cannot run: it says which and leads into
     * the app, and never keeps the Dictation for later. */
    enum class CannotRun { SIGNED_OUT, NO_ASSISTANT_SETTINGS, OFFLINE }

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
        /** The device has a speech recognizer: the microphone shows. */
        val dictationAvailable: Boolean = false,
        /** A Dictation is running. */
        val listening: Boolean = false,
        /** The last Dictation failed for a reason the user did not choose. */
        val dictationFailed: Boolean = false,
        /** Quick Dictation: the reply needs nothing more and the card
         * closes by itself after [AUTO_CLOSE_MILLIS]. */
        val closing: Boolean = false,
        /** Quick Dictation: why the card cannot run, null while it can. */
        val cannotRun: CannotRun? = null,
    )

    private val _uiState = MutableStateFlow(UiState(open = quick, dictationAvailable = recognizer != null))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var availabilityJob: Job? = null
    private var turnJob: Job? = null
    private var autoCloseJob: Job? = null

    /** One Dictation's own state: whether the microphone opened (its
     * start tone sounded) and whether the Assistant dropped it. */
    private class Dictation {
        var started = false
        var dropped = false
    }

    /** The Dictation running now, if any. */
    private var dictation: Dictation? = null

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
            var unreadable: Exception? = null
            val configured = try {
                settings.fetchStatus().configured
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                unreadable = error
                false
            }
            _uiState.update { it.copy(availabilityKnown = true, available = configured) }
            if (quick && !configured) {
                // An unreadable status is no answer: the card says why it
                // could not ask (a session refused, or no connection).
                cannotRun(
                    when (unreadable) {
                        null -> CannotRun.NO_ASSISTANT_SETTINGS
                        else -> cannotRunFor(unreadable) ?: CannotRun.OFFLINE
                    },
                )
            }
        }
    }

    /** Quick Dictation: the failures the card cannot run past — a session
     * the server refuses, Assistant Settings gone, no connection — or null
     * for one that is only this turn's. */
    private fun cannotRunFor(error: Exception): CannotRun? = when {
        (error as? ApiException)?.status == 401 -> CannotRun.SIGNED_OUT
        (error as? ApiException)?.status == 409 -> CannotRun.NO_ASSISTANT_SETTINGS
        error is IOException -> CannotRun.OFFLINE
        else -> null
    }

    /** Quick Dictation: the card cannot run. A listening Dictation is
     * dropped unsent, and none starts again. */
    private fun cannotRun(reason: CannotRun) {
        dropDictation()
        _uiState.update { it.copy(cannotRun = reason, pending = false, closing = false) }
    }

    fun open() {
        _uiState.update { it.copy(open = true) }
    }

    /** Close the Assistant and forget the conversation: the transcript,
     * the lines, the box and any turn in flight (its reply, if the server
     * still writes one, is dropped; its Drafts show on the Transactions
     * tab all the same). */
    fun close() {
        dropDictation()
        turnJob?.cancel()
        autoCloseJob?.cancel()
        transcript = emptyList()
        _uiState.update {
            it.copy(
                open = false,
                lines = emptyList(),
                text = "",
                pending = false,
                dictationFailed = false,
                closing = false,
            )
        }
    }

    fun onTextChange(value: String) {
        // Typing means the user chose the box: the Dictation gives way.
        dropDictation()
        _uiState.update { it.copy(text = value) }
    }

    /** Start a Dictation: when the Assistant opens and on a microphone tap,
     * once the microphone permission is granted. None starts while the
     * Assistant is closed, a reply is pending or one is already listening. */
    fun startDictation() {
        val recognizer = recognizer ?: return
        val state = _uiState.value
        if (!state.open || state.pending || state.cannotRun != null || dictation != null) return
        val dictation = Dictation()
        this.dictation = dictation
        _uiState.update { it.copy(listening = true, dictationFailed = false) }
        recognizer.start(
            recognitionLanguage(locale),
            object : DictationRecognizer.Listener {
                override fun onReadyForSpeech() {
                    if (dictation.dropped) return
                    dictation.started = true
                    tones.playStart()
                }

                override fun onResult(heard: String) {
                    if (!end(dictation)) return
                    // What was heard is the next turn, after anything already typed.
                    sendSentence("${_uiState.value.text.trim()} $heard".trim())
                }

                override fun onNoMatch() {
                    end(dictation)
                }

                override fun onFailure() {
                    if (!end(dictation)) return
                    _uiState.update { it.copy(dictationFailed = true) }
                }
            },
        )
    }

    /** End the running Dictation early (the second microphone tap): what
     * it heard so far is still sent. */
    fun stopDictation() {
        if (dictation != null) recognizer?.stop()
    }

    /** [dictation] ended: the end tone answers its start tone. False when it
     * was dropped, whose late end must change nothing. */
    private fun end(dictation: Dictation): Boolean {
        if (dictation.dropped) return false
        this.dictation = null
        _uiState.update { it.copy(listening = false) }
        if (dictation.started) tones.playEnd()
        return true
    }

    /** End the running Dictation, if any, without sending or sounding. */
    private fun dropDictation() {
        val dictation = dictation ?: return
        dictation.dropped = true
        this.dictation = null
        _uiState.update { it.copy(listening = false) }
        recognizer?.cancel()
    }

    override fun onCleared() {
        dropDictation()
    }

    /** Send what is typed in the box. */
    fun send() {
        // Sending the box is choosing the box: the Dictation gives way.
        dropDictation()
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
                if (quick) {
                    // The Assistant's one question waits for a spoken answer;
                    // anything else needs nothing more.
                    if (asksQuestion(result.reply)) startDictation() else closeSoon()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val apiError = error as? ApiException
                val reason = if (quick) cannotRunFor(error) else null
                if (reason != null) {
                    _uiState.update { it.copy(lines = it.lines.filterNot { line -> line === userLine }) }
                    cannotRun(reason)
                    return@launch
                }
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
                        // Quick Dictation has no box: the sentence is said again.
                        text = if (quick) "" else sentence,
                        pending = false,
                    )
                }
            }
        }
    }

    /** Quick Dictation: the user touched the card, which stays open. */
    fun holdOpen() {
        autoCloseJob?.cancel()
        _uiState.update { it.copy(closing = false) }
    }

    /** Quick Dictation: close the card [AUTO_CLOSE_MILLIS] after a reply
     * that needs nothing more. */
    private fun closeSoon() {
        autoCloseJob = viewModelScope.launch {
            delay(AUTO_CLOSE_MILLIS)
            close()
        }
        _uiState.update { it.copy(closing = true) }
    }

    companion object {
        /** Whether a reply asks the Assistant's one question (an ambiguous
         * Wallet, Category or Recurring name). The server marks no question
         * apart from the reply's text, so a reply ending in a question mark
         * is one. */
        internal fun asksQuestion(reply: String): Boolean = reply.trimEnd().endsWith('?')

        /** How long a Quick Dictation card stays after a reply that needs
         * nothing more. */
        const val AUTO_CLOSE_MILLIS = 3_000L

        private const val HOURLY_BOUND = "You have reached the hourly limit of Assistant requests. Try again later."
        private const val COULD_NOT_ANSWER = "The Assistant could not answer. Try again."
    }
}
