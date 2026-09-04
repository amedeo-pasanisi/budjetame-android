package com.budjetame.android.ui.recurring

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.ui.theme.Slate400
import com.budjetame.android.ui.theme.Slate500
import com.budjetame.android.ui.theme.Slate700

/**
 * The edit modal's Occurrences section (web ADR-0026), the port of the
 * web RecurringCostForm.tsx / RecurringIncomeForm.tsx section — shared by
 * the Costs and Incomes modals (ADR-0011 leaves the display layer free to
 * share pure UI). Edit mode only: a definition under creation has no id
 * yet, so the caller never shows it then.
 *
 * Rows are the definition's non-Paid Occurrences in the read's own order —
 * the next incoming Unpaid one on top, then every other row newest-first
 * down to the oldest (today first among the past) — and the section
 * renders that list verbatim: a client never sorts, groups, or reorders
 * it, so each row toggle's refreshed read re-renders exactly what the web
 * shows (skip the top row and it greys in place while the following one
 * appears above it). Skipped rows stay greyed with Un-skip, so every
 * excused Occurrence stays reachable; a row's own toggle works in any
 * order, and its button disables itself while the write is in flight so a
 * double tap cannot fire two writes (the write is idempotent anyway).
 */
@Composable
fun OccurrencesSection(
    occurrences: List<RecurringOccurrenceDto>?,
    error: String?,
    togglingDate: String?,
    onToggle: (RecurringOccurrenceDto) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = "Occurrences",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = Slate700,
        )
        error?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        when {
            occurrences == null && error == null -> Text(
                text = "Loading occurrences…",
                style = MaterialTheme.typography.labelSmall,
                color = Slate500,
                modifier = Modifier.padding(top = 4.dp),
            )
            occurrences != null -> OccurrenceRows(
                occurrences = occurrences,
                togglingDate = togglingDate,
                onToggle = onToggle,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = "Skip excuses an occurrence: it never counts as unpaid, " +
                "and a payment covers it only after un-skipping. Paid ones live in the ledger.",
            style = MaterialTheme.typography.labelSmall,
            color = Slate500,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** The rows in their bordered, divided container — the web section's
 * rounded-xl bordered white list. */
@Composable
private fun OccurrenceRows(
    occurrences: List<RecurringOccurrenceDto>,
    togglingDate: String?,
    onToggle: (RecurringOccurrenceDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
    ) {
        occurrences.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            OccurrenceRow(
                row = row,
                enabled = togglingDate != row.date,
                onToggle = { onToggle(row) },
            )
        }
    }
}

/**
 * One row: the Occurrence's own date — greyed with its "Skipped — un-skip
 * to pay it" caption when excused — and the row's own Skip/Un-skip pill.
 * The pill disables and dims while the row's write is in flight.
 */
@Composable
private fun OccurrenceRow(
    row: RecurringOccurrenceDto,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.date,
                // The web row type: text-sm — 14 sp; skipped rows read
                // text-slate-400, live ones font-medium text-slate-900.
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (row.skipped) FontWeight.Normal else FontWeight.Medium,
                color = if (row.skipped) Slate400 else MaterialTheme.colorScheme.onSurface,
            )
            if (row.skipped) {
                Text(
                    text = "Skipped — un-skip to pay it",
                    // The web caption type: text-xs text-slate-400 (ticket #44).
                    fontSize = 12.sp,
                    color = Slate400,
                )
            }
        }
        OccurrencePill(
            label = if (row.skipped) "Un-skip" else "Skip",
            enabled = enabled,
            onClick = onToggle,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** The row's Skip/Un-skip pill, the web button's port: a quiet, bordered
 * round button — the old card pill's look (web ADR-0026 moved the control
 * off the card into the rows). */
@Composable
private fun OccurrencePill(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
