package com.budjetame.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.ui.assistant.AssistantViewModel
import com.budjetame.android.ui.assistant.QuickDictationCannotRun
import com.budjetame.android.ui.assistant.QuickDictationChecking
import com.budjetame.android.ui.assistant.QuickDictationContent
import com.budjetame.android.ui.assistant.QuickDictationFrame
import com.budjetame.android.ui.assistant.QuickDictationNoRecognizer
import com.budjetame.android.ui.assistant.QuickDictationStart
import com.budjetame.android.ui.assistant.quickDictationStart
import com.budjetame.android.ui.theme.BudjetameTheme

/**
 * Quick Dictation (ticket #65; CONTEXT.md: Quick Dictation): the Assistant
 * opened from outside the app — the Quick Settings tile, the app-icon
 * shortcut, or the "Record Budjetame" launcher entry — as a voice-only card
 * over whatever is on screen. It reads the signed-in Account through the
 * app's own session, then runs the Assistant in quick mode: it listens at
 * once, sends each Dictation as a fresh conversation, listens again for
 * the Assistant's one question, and closes itself once the reply needs
 * nothing more.
 *
 * Its own task, kept out of Recents (see the manifest): the card is
 * transient, so it finishes when it leaves the screen, dropping a Dictation
 * still listening. Exercised only on a device.
 */
class QuickDictationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as BudjetameApplication).container
        setContent {
            BudjetameTheme {
                var start by remember { mutableStateOf<QuickDictationStart>(QuickDictationStart.Checking) }
                LaunchedEffect(Unit) { start = quickDictationStart(container.authRepository) }

                QuickDictationFrame(onClose = ::finish) {
                    when (val current = start) {
                        QuickDictationStart.Checking -> QuickDictationChecking()
                        is QuickDictationStart.CannotRun -> QuickDictationCannotRun(current.reason) { openApp(false) }
                        is QuickDictationStart.Ready -> {
                            val assistant: AssistantViewModel = viewModel {
                                AssistantViewModel(
                                    turns = container.assistantRepository,
                                    settings = container.assistantSettingsRepository,
                                    recognizer = container.dictationRecognizer,
                                    tones = container.dictationTones,
                                    locale = current.locale,
                                    quick = true,
                                )
                            }
                            val state by assistant.uiState.collectAsStateWithLifecycle()
                            // The auto-close closes the Assistant: the card goes with it.
                            LaunchedEffect(state.open) { if (!state.open) finish() }
                            val cannotRun = state.cannotRun
                            when {
                                cannotRun != null -> QuickDictationCannotRun(cannotRun) { openApp(false) }
                                !state.dictationAvailable -> QuickDictationNoRecognizer { openApp(false) }
                                else -> QuickDictationContent(
                                    state = state,
                                    onStartDictation = assistant::startDictation,
                                    onStopDictation = assistant::stopDictation,
                                    onTouch = assistant::holdOpen,
                                    onOpenInApp = { openApp(true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // A card left behind (home, another app, the screen off) is done.
        if (!isChangingConfigurations) finish()
    }

    private fun openApp(showTransactions: Boolean) {
        MainActivity.open(this, showTransactions)
        finish()
    }
}
