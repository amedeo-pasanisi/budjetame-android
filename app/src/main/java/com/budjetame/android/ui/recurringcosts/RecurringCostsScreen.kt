package com.budjetame.android.ui.recurringcosts

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.ui.common.RowEditButton
import com.budjetame.android.ui.theme.Slate500
import com.budjetame.android.util.Money

// The web app's Tailwind red palette, ported for the Backlog badge
// (RecurringCostsScreen.tsx, web ADR-0025 / ticket #45): the one signal
// of a non-empty Backlog is the red "N unpaid" badge — the amber badge
// and the Overdue mark are gone with the summary line.
private val RED_100 = Color(0xFFFEE2E2)
private val RED_700 = Color(0xFFB91C1C)

/**
 * The Recurring Costs side of the Recurring tab (ticket #22, web issue
 * #56): every Recurring Cost sorted by next due date, each row showing the
 * name, the amount, the interval, the next due date, and — when the derived
 * dates diverge, e.g. under a Backlog — the next Unpaid Occurrence date
 * (the one a new linked Expense would pay), plus the red "N unpaid"
 * Backlog badge — the one Backlog signal (web ADR-0025, ticket #45):
 * the Overdue mark and the summary line repeated the same fact in two
 * words, so they are gone.
 *
 * Row structure (web ADR-0026, ticket #46): like the Wallets rows
 * (ADR-0004 anatomy), a row is a tap surface with a sibling trailing ✎
 * inside one card. The tap surface (name, amount, next due, badge) sends
 * the ledger jump: the shell opens the Transactions tab pre-filtered to
 * this definition's linked Transactions — the card Skip/Un-skip button is
 * gone with the backend endpoint it pressed. The ✎ button opens the edit
 * modal — whose Occurrences section carries the per-Occurrence Skip/Un-skip
 * controls — and create, edit, and delete live there. The badge and the
 * dates are derived state from the API: they refresh whenever the list
 * reloads — after every write anywhere, via the data-version bump
 * (ADR-0002).
 */
@Composable
fun RecurringCostsScreen(
    recurringCosts: RecurringCostGateway,
    onLedgerJump: (LedgerJump) -> Unit = {},
) {
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
            else -> RecurringCostsList(
                state = state,
                viewModel = viewModel,
                onLedgerJump = onLedgerJump,
                modifier = Modifier.weight(1f),
            )
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
            onToggleOccurrence = viewModel::toggleOccurrence,
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
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onNewRecurringCost,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text("New recurring cost")
        }
    }
}

@Composable
private fun RecurringCostsList(
    state: RecurringCostsViewModel.UiState,
    viewModel: RecurringCostsViewModel,
    onLedgerJump: (LedgerJump) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(state.costs, key = { it.id }) { cost ->
            RecurringCostRow(
                cost = cost,
                onLedgerJump = { onLedgerJump(LedgerJump.RecurringCost(cost.id)) },
                onEdit = { viewModel.openEdit(cost) },
            )
        }
    }
}

/**
 * One Recurring Cost row (web ADR-0026 anatomy, ticket #46): the card's
 * main tap surface — name, interval · next due date and the red "N
 * unpaid" Backlog badge, plus the amount — sends the ledger jump: the
 * Transactions tab opens pre-filtered to this definition's linked
 * Transactions, the previous filters and search reset by the jump
 * (ADR-0004). The trailing ✎ (RowEditButton) opens the edit modal, whose
 * Occurrences section holds the per-Occurrence Skip/Un-skip controls —
 * the card Skip/Un-skip pill is gone (web ADR-0026). The next Unpaid
 * Occurrence date earns its own line when it differs from the next due
 * date — under a Backlog the next thing a new linked Expense would pay is
 * not the schedule's next due date — and is otherwise the very date the
 * next-due line already names. The badge is the one Backlog signal (web
 * ADR-0025, ticket #45): it shows while the Backlog is non-empty.
 */
@Composable
private fun RecurringCostRow(
    cost: RecurringCostDto,
    onLedgerJump: () -> Unit,
    onEdit: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        // The web card look: no gray outline — the soft shadow alone
        // separates the card from the page (ticket #44).
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(modifier = Modifier.clip(shape)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // The whole tap surface is the ledger jump — the ✎ is its
                // sibling, never nested (ADR-0004 anatomy).
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onLedgerJump)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cost.name,
                        // The web row type: font-medium 14 sp (ticket #44).
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "${intervalText(cost.interval_value, cost.interval_unit)} · " +
                            "next due ${cost.next_due_date}",
                        // The web subtitle type: text-xs text-slate-500 (ticket #44).
                        fontSize = 12.sp,
                        color = Slate500,
                    )
                    if (cost.next_unpaid_occurrence_date != cost.next_due_date) {
                        Text(
                            text = "Next unpaid ${cost.next_unpaid_occurrence_date}",
                            fontSize = 12.sp,
                            color = Slate500,
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
                            background = RED_100,
                            content = RED_700,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            RowEditButton(name = cost.name, onEdit = onEdit)
        }
    }
}

/** A small rounded chip — the Backlog badge. */
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
