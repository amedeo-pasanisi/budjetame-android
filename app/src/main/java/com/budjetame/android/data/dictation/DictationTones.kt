package com.budjetame.android.data.dictation

/** The two short tones that frame a Dictation (ticket #69): one when it is
 * really listening, one at its end. */
interface DictationTones {
    fun playStart()
    fun playEnd()
}
