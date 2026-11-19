package com.budjetame.android.ui.assistant

import com.budjetame.android.data.auth.ApiAuthRepository
import kotlinx.coroutines.CancellationException

/** Where a Quick Dictation card stands before it listens (ticket #65). */
sealed interface QuickDictationStart {
    /** The signed-in Account is being read. */
    data object Checking : QuickDictationStart

    data class CannotRun(val reason: AssistantViewModel.CannotRun) : QuickDictationStart

    /** Signed in: a Dictation listens in [locale], the Account's Locale. */
    data class Ready(val locale: String) : QuickDictationStart
}

/**
 * Read the signed-in Account through the app's own session, as the app does
 * at start: no session, or one the server refuses, is signed out; a server
 * that cannot be reached (or answers anything else) is offline.
 */
suspend fun quickDictationStart(auth: ApiAuthRepository): QuickDictationStart = try {
    when (val account = auth.fetchCurrentAccount()) {
        null -> QuickDictationStart.CannotRun(AssistantViewModel.CannotRun.SIGNED_OUT)
        else -> QuickDictationStart.Ready(locale = account.language)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    QuickDictationStart.CannotRun(AssistantViewModel.CannotRun.OFFLINE)
}
