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
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_NO_MATCH))
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertFalse(isDictationFailure(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }

    @Test
    fun `a recognizer that could not listen is a failure`() {
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_NETWORK))
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_AUDIO))
        assertTrue(isDictationFailure(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }
}
