package com.budjetame.android.ui.assistant

import com.budjetame.android.data.auth.ApiAuthRepository
import com.budjetame.android.data.api.ApiException
import java.io.IOException
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
 * that cannot be reached, or answers with any other failure, cannot be
 * reached either way.
 */
suspend fun quickDictationStart(auth: ApiAuthRepository): QuickDictationStart = try {
    when (val account = auth.fetchCurrentAccount()) {
        null -> QuickDictationStart.CannotRun(AssistantViewModel.CannotRun.SIGNED_OUT)
        else -> QuickDictationStart.Ready(locale = account.language)
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    QuickDictationStart.CannotRun(cannotRunFor(error) ?: AssistantViewModel.CannotRun.OFFLINE)
}

/** The failures Quick Dictation cannot run past — a session the server
 * refuses, Assistant Settings gone, no connection — or null for one that is
 * only a turn's (the caller decides). */
internal fun cannotRunFor(error: Exception): AssistantViewModel.CannotRun? =
    when ((error as? ApiException)?.status) {
        401 -> AssistantViewModel.CannotRun.SIGNED_OUT
        409 -> AssistantViewModel.CannotRun.NO_ASSISTANT_SETTINGS
        else -> if (error is IOException) AssistantViewModel.CannotRun.OFFLINE else null
    }
