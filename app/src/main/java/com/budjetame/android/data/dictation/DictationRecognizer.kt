package com.budjetame.android.data.dictation

/**
 * The device's speech recognizer behind one small seam (ticket #69, the
 * web's `speechRecognition.ts`): the Assistant's Dictation listens through
 * it, and its tests drive a fake. One recognition at a time: [start] begins
 * one, [stop] ends it early with what was heard so far, [cancel] drops it —
 * after a cancel no callback is delivered.
 *
 * Each recognition ends with exactly one of [Listener.onResult],
 * [Listener.onNoMatch] or [Listener.onFailure]. Callbacks arrive on the main
 * thread.
 */
interface DictationRecognizer {

    /** Begin listening in [language], a BCP 47 tag such as `it-IT`. */
    fun start(language: String, listener: Listener)

    /** Stop listening: what was heard so far is still delivered. */
    fun stop()

    /** Drop the recognition: nothing more is delivered. */
    fun cancel()

    interface Listener {
        /** The microphone is open: the recognizer is really listening. */
        fun onReadyForSpeech()

        /** The recognition ended with what was heard. */
        fun onResult(heard: String)

        /** The recognition ended having heard nothing — silence, or a
         * refusal the user chose (the microphone permission denied). */
        fun onNoMatch()

        /** The recognition failed for a reason the user did not choose. */
        fun onFailure()
    }
}

/** The BCP 47 tag a Dictation listens in, from the Account's Locale as the
 * server serves it: Italian is `it-IT`, everything else `en-US`. */
fun recognitionLanguage(locale: String): String = if (locale == "it") "it-IT" else "en-US"

/** The two short tones that frame a Dictation: one when it is really
 * listening, one at its end. */
interface DictationTones {
    fun playStart()
    fun playEnd()
}
