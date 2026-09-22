package com.budjetame.android.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val RED_200 = Color(0xFFFECACA)
private val RED_600 = Color(0xFFDC2626)
private val INDIGO_50 = Color(0xFFEEF2FF)
private val INDIGO_600 = Color(0xFF4F46E5)

/**
 * The Freeze section shown in edit modals for active records: a divider, a
 * heading, a description, and a red outlined button with tap-again
 * confirmation. When [canFreeze] is false the button is disabled and shows
 * [disabledLabel] instead — without a red border.
 */
@Composable
fun FreezeSection(
    canFreeze: Boolean,
    isFreezing: Boolean,
    confirmingFreeze: Boolean,
    freezeError: String?,
    onFreeze: () -> Unit,
    disabledLabel: String? = null,
    heading: String = "Freeze",
    description: String = "",
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = heading,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    if (description.isNotEmpty()) {
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    freezeError?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Button(
        onClick = onFreeze,
        enabled = canFreeze && !isFreezing,
        colors = if (confirmingFreeze) {
            ButtonDefaults.buttonColors(containerColor = RED_600, contentColor = Color.White)
        } else {
            ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = RED_600)
        },
        border = if (canFreeze && !confirmingFreeze) BorderStroke(1.dp, RED_200) else null,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(
            when {
                isFreezing -> "Freezing…"
                disabledLabel != null -> disabledLabel
                confirmingFreeze -> "Tap again to confirm freeze"
                else -> "Freeze"
            },
        )
    }
}

/**
 * The Unfreeze section shown in edit modals for frozen records: a divider,
 * a heading, a description, and an indigo outlined button.
 */
@Composable
fun UnfreezeSection(
    isFreezing: Boolean,
    onUnfreeze: () -> Unit,
    heading: String = "Unfreeze",
    description: String = "",
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = heading,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    if (description.isNotEmpty()) {
        Text(
            text = description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Button(
        onClick = onUnfreeze,
        enabled = !isFreezing,
        colors = ButtonDefaults.buttonColors(
            containerColor = INDIGO_50,
            contentColor = INDIGO_600,
        ),
        border = BorderStroke(1.dp, INDIGO_200),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(if (isFreezing) "Unfreezing…" else "Unfreeze")
    }
}

private val INDIGO_200 = Color(0xFFC7D2FE)