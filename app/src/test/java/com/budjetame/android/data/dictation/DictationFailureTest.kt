package com.budjetame.android.data.dictation

import android.speech.SpeechRecognizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the platform recognizer's errors are a failure the user did not
 * choose (CONTEXT.md: Dictation): only those make the Assistant say so. */
class DictationFailureTest {

    @Test
    fun `hearing nothing and a refused microphone are no failure`() {
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_NO_MATCH, stopped = false))
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_SPEECH_TIMEOUT, stopped = false))
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, stopped = false))
    }

    @Test
    fun `a recognizer that could not listen is a failure`() {
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_NETWORK, stopped = false))
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_AUDIO, stopped = false))
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, stopped = false))
    }

    @Test
    fun `a client error after the user stopped is no failure, but one unprompted is`() {
        // Many recognizers answer a stop before any speech with ERROR_CLIENT.
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_CLIENT, stopped = true))
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_CLIENT, stopped = false))
    }
}
