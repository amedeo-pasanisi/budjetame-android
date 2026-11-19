package com.budjetame.android.ui.assistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.budjetame.android.ui.theme.Slate500

/**
 * Quick Dictation's card (ticket #65; CONTEXT.md: Quick Dictation): a card at
 * the bottom of the screen, over whatever the phone shows, without the app's
 * tabs. A touch outside it closes it; [content] is what it shows.
 */
@Composable
fun QuickDictationFrame(
    onClose: () -> Unit,
    /** Any touch on the card: it cancels the auto-close. */
    onTouch: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // The see-through rest of the screen, a sibling under the card so a
        // touch on the card never reaches it.
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // Watch every touch without taking it from the buttons.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onTouch()
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Record Budjetame",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                content()
            }
        }
    }
}

/** The card's one line while the signed-in Account is read. */
@Composable
fun QuickDictationChecking() {
    StatusText("Opening Budjetame…")
}

/** The card cannot run: one line saying why, and a way into the app. The
 * Dictation is never kept for later. */
@Composable
fun QuickDictationCannotRun(reason: AssistantViewModel.CannotRun, onOpenApp: () -> Unit) {
    Text(
        text = when (reason) {
            AssistantViewModel.CannotRun.SIGNED_OUT -> "You are signed out of Budjetame."
            AssistantViewModel.CannotRun.NO_ASSISTANT_SETTINGS ->
                "The Assistant is not set up: add the Assistant Settings in the app's Settings."
            AssistantViewModel.CannotRun.OFFLINE -> "Budjetame cannot be reached: check your connection and try again."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    OpenAppButton(onOpenApp)
}

/** The device has no speech recognizer: Quick Dictation is voice only. */
@Composable
fun QuickDictationNoRecognizer(onOpenApp: () -> Unit) {
    Text(
        text = "This phone cannot take dictation: type the sentence in the app.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OpenAppButton(onOpenApp)
}

@Composable
private fun OpenAppButton(onOpenApp: () -> Unit) {
    Button(
        onClick = onOpenApp,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text("Open Budjetame")
    }
}

/**
 * The card at work: the reply lines, the microphone (tap to dictate again or
 * to end the Dictation listening) and "open in app"; no box to type in. A
 * Dictation starts as soon as the card shows and the Assistant is known to
 * answer.
 */
@Composable
fun QuickDictationContent(
    state: AssistantViewModel.UiState,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onOpenInApp: () -> Unit,
) {
    val dictate = rememberDictate(onStartDictation)
    // The card starts a Dictation at once, as soon as the Assistant is known
    // to answer: once, as it shows.
    LaunchedEffect(state.available) { if (state.available) dictate() }

    Column {
        val listState = rememberLazyListState()
        LaunchedEffect(state.lines.size) {
            if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.size - 1)
        }
        if (state.lines.isEmpty() && !state.listening) {
            StatusText("Say what you spent or received: it lands as a Draft Transaction on the Transactions tab.")
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 280.dp),
        ) {
            items(state.lines) { line -> LineBubble(line) }
        }
        when {
            state.listening -> StatusText("Listening…")
            state.pending -> StatusText("The Assistant is writing…")
            state.dictationFailed -> Text(
                text = "Dictation did not work. Tap the microphone to try again.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            MicrophoneButton(
                listening = state.listening,
                enabled = !state.pending,
                onClick = { if (state.listening) onStopDictation() else dictate() },
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenInApp) {
                Text("Open in app")
            }
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(text = text, fontSize = 12.sp, color = Slate500, modifier = Modifier.padding(top = 8.dp))
}
