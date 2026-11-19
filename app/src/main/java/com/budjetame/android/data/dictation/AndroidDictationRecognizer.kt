package com.budjetame.android.data.dictation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * The [DictationRecognizer] over Android's [SpeechRecognizer]: one platform
 * recognizer per Dictation, made at [start] and destroyed at its end, so a
 * recognizer left busy by one Dictation never blocks the next. A callback
 * of a recognizer no longer current (cancelled, or replaced) is ignored.
 *
 * Exercised only on a device: the unit tests drive a fake of the seam.
 */
class AndroidDictationRecognizer private constructor(private val context: Context) : DictationRecognizer {

    private var current: SpeechRecognizer? = null

    /** The user ended the current recognition early ([stop]). */
    private var stopped = false

    override fun start(language: String, listener: DictationRecognizer.Listener) {
        cancel()
        val recognizer = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (_: Exception) {
            listener.onFailure()
            return
        }
        current = recognizer
        stopped = false
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (current === recognizer) listener.onReadyForSpeech()
            }

            override fun onResults(results: Bundle?) {
                if (!finish(recognizer)) return
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (heard.isEmpty()) listener.onNoMatch() else listener.onResult(heard)
            }

            override fun onError(error: Int) {
                val stoppedByUser = stopped
                if (!finish(recognizer)) return
                if (isDictationFailure(error, stoppedByUser)) listener.onFailure() else listener.onNoMatch()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer.startListening(intent)
    }

    override fun stop() {
        val recognizer = current ?: return
        stopped = true
        recognizer.stopListening()
    }

    override fun cancel() {
        val recognizer = current ?: return
        current = null
        recognizer.cancel()
        recognizer.destroy()
    }

    /** The recognition of [recognizer] ended: release it. False when it is
     * no longer the current one, whose ending must be ignored. */
    private fun finish(recognizer: SpeechRecognizer): Boolean {
        if (current !== recognizer) return false
        current = null
        recognizer.destroy()
        return true
    }

    companion object {
        /** The recognizer, or null where the device has none (no
         * recognition service): the Assistant then shows no microphone. */
        fun createOrNull(context: Context): DictationRecognizer? =
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                AndroidDictationRecognizer(context.applicationContext)
            } else {
                null
            }
    }
}

/** Whether a [SpeechRecognizer] error is a failure the user did not choose.
 * Hearing nothing (no match, silence until the timeout) and a refused
 * microphone are not (web `isDictationFailure`); nor is the client error
 * many recognizers answer a [AndroidDictationRecognizer.stop] before any
 * speech with, once the user [stopped] it — the web's `aborted`. */
fun isDictationFailure(error: Int, stopped: Boolean): Boolean =
    error !in QUIET_ERRORS && !(stopped && error == SpeechRecognizer.ERROR_CLIENT)

private val QUIET_ERRORS = setOf(
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
)
