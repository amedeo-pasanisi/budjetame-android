package com.budjetame.android.ui.recurringcosts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.util.Money

// The web app's Tailwind palette, ported for the Overdue and Backlog
// badges (RecurringCostsScreen.tsx).
private val RED_100 = Color(0xFFFEE2E2)
private val RED_700 = Color(0xFFB91C1C)
private val AMBER_100 = Color(0xFFFEF3C7)
private val AMBER_800 = Color(0xFF92400E)

/**
 * The Recurring Costs side of the Recurring tab (ticket #22, web issue
 * #56): every Recurring Cost sorted by next due date, each row showing the
 * name, the amount, the interval, the next due date, and — when the derived
 * dates diverge, e.g. under a Backlog — the next Unpaid Occurrence date
 * (the one a new linked Expense would pay), plus the "N unpaid" Backlog
 * badge and the Overdue mark. Beside each row sits the Skip/Un-skip button
 * (ADR-0016, ticket #24): its label comes from the definition's
 * `next_skip_action` — "Un-skip" once the whole Backlog is excused — and a
 * press swaps the row with the backend's refreshed definition, so the
 * badge, the dates, and the label re-render from the response. The summary
 * line on top answers "what remains to pay" at a glance. Create, edit, and
 * delete live here, in a modal. The badge, the mark, and the dates are
 * derived state from the API: they refresh whenever the list reloads —
 * after every write anywhere, via the data-version bump (ADR-0002).
 */
@Composable
fun RecurringCostsScreen(recurringCosts: RecurringCostGateway) {
    val viewModel: RecurringCostsViewModel = viewModel { RecurringCostsViewModel(recurringCosts) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        RecurringCostsHeader(onNewRecurringCost = viewModel::openCreate)

        val loadError = state.loadError
        when {
            state.loading -> MessageBody(
                text = "Loading recurring costs…",
                modifier = Modifier.weight(1f),
            )
            loadError != null -> LoadErrorBody(
                message = loadError,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f),
            )
            state.costs.isEmpty() -> MessageBody(
                text = "No recurring costs yet. Add your first one to track what's due.",
                modifier = Modifier.weight(1f),
            )
            else -> RecurringCostsList(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
        }
    }

    state.modal?.let { modal ->
        RecurringCostsModal(
            modal = modal,
            onNameChange = viewModel::onNameChange,
            onAmountChange = viewModel::onAmountChange,
            onIntervalValueChange = viewModel::onIntervalValueChange,
            onIntervalUnitChange = viewModel::onIntervalUnitChange,
            onStartDateChange = viewModel::onStartDateChange,
            onDueDayChange = viewModel::onDueDayChange,
            onDueMonthChange = viewModel::onDueMonthChange,
            onSubmit = viewModel::submit,
            onDelete = viewModel::onDeleteTap,
            onClose = viewModel::closeModal,
        )
    }
}

@Composable
private fun RecurringCostsHeader(onNewRecurringCost: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Recurring Costs",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onNewRecurringCost) {
            Text("New recurring cost")
        }
    }
}

@Composable
private fun RecurringCostsList(
    state: RecurringCostsViewModel.UiState,
    viewModel: RecurringCostsViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        state.actionError?.let { message ->
            item(key = "action-error") {
                ActionErrorText(message = message)
            }
        }
        item(key = "summary") {
            SummaryLine(
                overdueCount = state.overdueCount,
                unpaidCount = state.unpaidCount,
            )
        }
        items(state.costs, key = { it.id }) { cost ->
            RecurringCostRow(
                cost = cost,
                toggling = state.togglingId == cost.id,
                onClick = { viewModel.openEdit(cost) },
                onToggleSkip = { viewModel.toggleSkip(cost) },
            )
        }
    }
}

/** A failed toggle's message (the web screen's inline error paragraph):
 * shown above the summary, the held rows still on screen — only the action
 * failed. Cleared by the next successful reload or the next press. */
@Composable
private fun ActionErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

/** The summary pill (web issue #58): "X costs overdue · N unpaid
 * occurrences" — totals over the loaded definitions. */
@Composable
private fun SummaryLine(overdueCount: Int, unpaidCount: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Text(
            text = "$overdueCount " +
                (if (overdueCount == 1) "cost overdue" else "costs overdue") +
                " · $unpaidCount " +
                (if (unpaidCount == 1) "unpaid occurrence" else "unpaid occurrences"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * One Recurring Cost row: the clickable card (name and interval · next due
 * date on the left — the Overdue mark under them — amount and the "N
 * unpaid" Backlog badge on the right) with the Skip/Un-skip pill beside
 * it, vertically centered like the web screen's row button. The next Unpaid
 * Occurrence date earns its own line when it differs from the next due
 * date — under a Backlog the next thing a new linked Expense would pay is
 * not the schedule's next due date — and is otherwise the very date the
 * next-due line already names. A press on the pill skips or un-skips (its
 * own in-flight toggle disables it, so a double tap cannot flip the state
 * twice); the card still opens the edit modal.
 */
@Composable
private fun RecurringCostRow(
    cost: RecurringCostDto,
    toggling: Boolean,
    onClick: () -> Unit,
    onToggleSkip: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 1.dp,
            modifier = Modifier.weight(1f),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cost.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${intervalText(cost.interval_value, cost.interval_unit)} · " +
                            "next due ${cost.next_due_date}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (cost.next_unpaid_occurrence_date != cost.next_due_date) {
                        Text(
                            text = "Next unpaid ${cost.next_unpaid_occurrence_date}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cost.overdue) {
                        Badge(
                            text = "Overdue",
                            background = RED_100,
                            content = RED_700,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = Money.formatEuros(cost.amount),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (cost.backlog_count > 0) {
                        Badge(
                            text = "${cost.backlog_count} unpaid",
                            background = AMBER_100,
                            content = AMBER_800,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
        SkipPill(
            label = skipToggleLabel(cost.next_skip_action),
            enabled = !toggling,
            onClick = onToggleSkip,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A small rounded chip — the Overdue mark and the Backlog badge. */
@Composable
private fun Badge(
    text: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

/**
 * The Skip/Un-skip button (ADR-0016), the web pill's port: a quiet,
 * bordered round button beside the card — the card itself still opens the
 * edit modal. The label comes from the definition's `next_skip_action`;
 * while the row's own toggle is in flight the pill disables itself and
 * dims, so a double tap cannot flip the state twice (skip then un-skip).
 */
@Composable
private fun SkipPill(
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
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
