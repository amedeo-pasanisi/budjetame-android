package com.budjetame.android.ui.assistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.budjetame.android.ui.theme.Slate500

/**
 * The Assistant (ticket #68, the web's AssistantModal): a full-screen chat
 * over whatever tab is showing — the transcript, a text box, the microphone
 * and Send. The conversation lives in [AssistantViewModel] while the dialog
 * shows and is forgotten when it closes.
 *
 * Dictation (ticket #69): opening the dialog starts one, and the microphone
 * starts another or ends the one listening. Each start first holds the
 * microphone permission ([rememberDictate]). Where the device has no
 * recognizer there is no microphone.
 */
@Composable
fun AssistantDialog(
    state: AssistantViewModel.UiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onClose: () -> Unit,
) {
    val dictate = rememberDictate(onStartDictation)
    // Opening the Assistant starts a Dictation: once per open, as the dialog
    // leaves the composition when it closes.
    LaunchedEffect(Unit) {
        if (state.dictationAvailable) dictate()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Assistant",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Close the Assistant")
                    }
                }
                Transcript(
                    state = state,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                if (state.dictationFailed) {
                    Text(
                        text = "Dictation did not work. Type the sentence instead.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.listening) {
                    Text(
                        text = "Listening…",
                        fontSize = 12.sp,
                        color = Slate500,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = onTextChange,
                        placeholder = { Text("e.g. 30 euro at the supermarket with the card") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("assistant-input"),
                    )
                    if (state.dictationAvailable) {
                        MicrophoneButton(
                            listening = state.listening,
                            enabled = !state.pending,
                            onClick = { if (state.listening) onStopDictation() else dictate() },
                        )
                    }
                    Button(
                        onClick = onSend,
                        enabled = !state.pending && state.text.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(if (state.pending) "Sending…" else "Send")
                    }
                }
            }
        }
    }
}

/** The microphone: starts a Dictation, or ends the one listening; shown
 * pressed while it listens, and disabled while a reply is pending. */
@Composable
internal fun MicrophoneButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = if (listening) {
            IconButtonDefaults.outlinedIconButtonColors(
                containerColor = colors.errorContainer,
                contentColor = colors.onErrorContainer,
            )
        } else {
            IconButtonDefaults.outlinedIconButtonColors()
        },
        modifier = Modifier.testTag("assistant-microphone"),
    ) {
        Icon(
            imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (listening) "Stop dictating" else "Dictate",
        )
    }
}

/** The conversation as shown: the user's sentences on the right, the
 * written replies on the left, a failure line apart; the newest line kept
 * in view. */
@Composable
private fun Transcript(state: AssistantViewModel.UiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.lines.size, state.pending) {
        val last = state.lines.size + (if (state.pending) 1 else 0) - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        if (state.lines.isEmpty() && !state.pending) {
            Text(
                text = if (state.dictationAvailable) {
                    "Say or type what you spent or received: each Draft Transaction lands on the Transactions tab."
                } else {
                    "Type what you spent or received: each Draft Transaction lands on the Transactions tab."
                },
                fontSize = 12.sp,
                color = Slate500,
            )
        }
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("assistant-log"),
        ) {
            items(state.lines) { line -> LineBubble(line) }
            if (state.pending) {
                item {
                    Text(text = "The Assistant is writing…", fontSize = 12.sp, color = Slate500)
                }
            }
        }
    }
}

@Composable
internal fun LineBubble(line: AssistantViewModel.Line) {
    val user = line.speaker == AssistantViewModel.Speaker.USER
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when {
        user -> colors.primary to colors.onPrimary
        line.failure -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Box(
        contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = line.text,
            color = foreground,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(background, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = "${if (user) "You" else "Assistant"}: ${line.text}" },
        )
    }
}
