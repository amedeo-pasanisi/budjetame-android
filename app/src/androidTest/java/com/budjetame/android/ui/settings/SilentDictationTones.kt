package com.budjetame.android.ui.settings

import com.budjetame.android.data.dictation.DictationTones

/** The Dictation tones of the shell tests, beside [NoAssistantGateway]:
 * they never open the Assistant, so nothing is ever played. */
class SilentDictationTones : DictationTones {
    override fun playStart() {}
    override fun playEnd() {}
}
