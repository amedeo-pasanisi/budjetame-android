package com.budjetame.android.ui.undo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.budjetame.android.ui.theme.Indigo600
import com.budjetame.android.ui.theme.Red600
import com.budjetame.android.ui.theme.Slate400
import com.budjetame.android.ui.theme.Slate700

/**
 * The shell's Undo stack on screen (web UndoToastStack, ticket #58): up to
 * three entries at the bottom, newest at the bottom edge (the web's
 * flex-col-reverse), each naming what was deleted — "Transaction deleted ·
 * Coffee" — with its own visible `{remaining}s` countdown, Undo and
 * Dismiss. A failed Undo shows its message on that entry. Material's
 * SnackbarHost shows one message at a time with fixed durations, hence
 * this small custom stack.
 */
@Composable
fun UndoToastStack(
    state: UndoState,
    onUndo: (key: String) -> Unit,
    onDismiss: (key: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.entries.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .widthIn(max = 448.dp)
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("undo-stack"),
    ) {
        // Newest first in the stack; the newest sits nearest the bottom bar.
        state.entries.asReversed().forEach { entry ->
            key(entry.key) {
                UndoToast(
                    entry = entry,
                    remainingSeconds = state.remainingSeconds(entry),
                    onUndo = { onUndo(entry.key) },
                    onDismiss = { onDismiss(entry.key) },
                )
            }
        }
    }
}

@Composable
private fun UndoToast(
    entry: UndoEntry,
    remainingSeconds: Int,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                            append(entry.deleted.headline)
                        }
                        entry.deleted.label?.let { append(" · $it") }
                    },
                    fontSize = 14.sp,
                    color = Slate700,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${remainingSeconds}s",
                    fontSize = 12.sp,
                    color = Slate400,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Button(
                    onClick = onUndo,
                    enabled = !entry.undoing,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo600, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text("Undo", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = Slate400,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            entry.error?.let { error ->
                Text(
                    text = error,
                    fontSize = 12.sp,
                    color = Red600,
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                )
            }
        }
    }
}
