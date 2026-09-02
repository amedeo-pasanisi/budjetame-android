package com.budjetame.android.ui.recurringincomes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.ui.recurringcosts.intervalText
import com.budjetame.android.util.Money

// The web app's Tailwind palette, ported for the Overdue and Backlog
// badges (RecurringIncomesScreen.tsx).
private val RED_100 = Color(0xFFFEE2E2)
private val RED_700 = Color(0xFFB91C1C)
private val AMBER_100 = Color(0xFFFEF3C7)
private val AMBER_800 = Color(0xFF92400E)

/**
 * The Recurring Incomes side of the Recurring tab (ticket #23, web issue
 * #60), mirroring the Costs side (ADR-0011): every Recurring Income sorted
 * by next due date, each row showing the name, the amount, the interval,
 * the next due date, and — when the derived dates diverge, e.g. under a
 * Backlog — the next Unpaid Occurrence date (the one a new linked Income
 * would pay), plus the "N unpaid" Backlog badge and the Overdue mark. The
 * summary line on top answers "what remains to receive" at a glance.
 * Create, edit, and delete live here, in a modal. The badge, the mark, and
 * the dates are derived state from the API: they refresh whenever the list
 * reloads — after every write anywhere, via the data-version bump
 * (ADR-0002).
 */
@Composable
fun RecurringIncomesScreen(recurringIncomes: RecurringIncomeGateway) {
    val viewModel: RecurringIncomesViewModel = viewModel {
        RecurringIncomesViewModel(recurringIncomes)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        RecurringIncomesHeader(onNewRecurringIncome = viewModel::openCreate)

        val loadError = state.loadError
        when {
            state.loading -> MessageBody(
                text = "Loading recurring incomes…",
                modifier = Modifier.weight(1f),
            )
            loadError != null -> LoadErrorBody(
                message = loadError,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f),
            )
            state.incomes.isEmpty() -> MessageBody(
                text = "No recurring incomes yet. Add your first one to track what's due.",
                modifier = Modifier.weight(1f),
            )
            else -> RecurringIncomesList(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
        }
    }

    state.modal?.let { modal ->
        RecurringIncomesModal(
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
private fun RecurringIncomesHeader(onNewRecurringIncome: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Recurring Incomes",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onNewRecurringIncome) {
            Text("New recurring income")
        }
    }
}

@Composable
private fun RecurringIncomesList(
    state: RecurringIncomesViewModel.UiState,
    viewModel: RecurringIncomesViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        item(key = "summary") {
            SummaryLine(
                overdueCount = state.overdueCount,
                unpaidCount = state.unpaidCount,
            )
        }
        items(state.incomes, key = { it.id }) { income ->
            RecurringIncomeRow(
                income = income,
                onClick = { viewModel.openEdit(income) },
            )
        }
    }
}

/** The summary pill (web issue #62): "X incomes overdue · N unpaid
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
                (if (overdueCount == 1) "income overdue" else "incomes overdue") +
                " · $unpaidCount " +
                (if (unpaidCount == 1) "unpaid occurrence" else "unpaid occurrences"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * One Recurring Income row: name and interval · next due date on the left
 * (the Overdue mark under them), amount and the "N unpaid" Backlog badge on
 * the right. The next Unpaid Occurrence date earns its own line when it
 * differs from the next due date — under a Backlog the next thing a new
 * linked Income would pay is not the schedule's next due date — and is
 * otherwise the very date the next-due line already names.
 */
@Composable
private fun RecurringIncomeRow(income: RecurringIncomeDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = income.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${intervalText(income.interval_value, income.interval_unit)} · " +
                        "next due ${income.next_due_date}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (income.next_unpaid_occurrence_date != income.next_due_date) {
                    Text(
                        text = "Next unpaid ${income.next_unpaid_occurrence_date}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (income.overdue) {
                    Badge(
                        text = "Overdue",
                        background = RED_100,
                        content = RED_700,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text(
                    text = Money.formatEuros(income.amount),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (income.backlog_count > 0) {
                    Badge(
                        text = "${income.backlog_count} unpaid",
                        background = AMBER_100,
                        content = AMBER_800,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
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
