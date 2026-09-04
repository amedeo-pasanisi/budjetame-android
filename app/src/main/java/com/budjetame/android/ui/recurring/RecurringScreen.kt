package com.budjetame.android.ui.recurring

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.ui.recurringcosts.RecurringCostsScreen
import com.budjetame.android.ui.recurringincomes.RecurringIncomesScreen

/** The Recurring tab's two sides, in the toggle's order. */
private enum class RecurringSide { COSTS, INCOMES }

/**
 * The Recurring tab (web issue #60): a Costs | Incomes toggle above the two
 * sides, mirroring the web app's RecurringScreen.tsx + recurringSide.ts.
 * Default Costs; the last side is remembered for the app session — the
 * saved state survives the tab leaving the composition on a tab switch
 * (the pager disposes the page; its per-tab saveable registry keeps the
 * state, ADR-0003) and resets on app load, exactly when the web's
 * module-level memory resets. The Costs
 * side renders exactly as before; the Incomes side mirrors it (ADR-0011).
 * The two sides' ViewModels live in the shell's Activity-scoped store, so
 * a toggled-away side keeps its loaded data and its background refetches
 * (ADR-0002/0003) — a toggle back renders instantly, never stale.
 * The ledger jump (web ADR-0026) rides through: a card's whole-surface
 * tap opens the Transactions tab pre-filtered to that definition.
 */
@Composable
fun RecurringScreen(
    recurringCosts: RecurringCostGateway,
    recurringIncomes: RecurringIncomeGateway,
    onLedgerJump: (LedgerJump) -> Unit = {},
) {
    var side by rememberSaveable { mutableStateOf(RecurringSide.COSTS) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SideButton(
                label = "Costs",
                selected = side == RecurringSide.COSTS,
                onClick = { side = RecurringSide.COSTS },
                modifier = Modifier.weight(1f),
            )
            SideButton(
                label = "Incomes",
                selected = side == RecurringSide.INCOMES,
                onClick = { side = RecurringSide.INCOMES },
                modifier = Modifier.weight(1f),
            )
        }
        when (side) {
            RecurringSide.COSTS -> RecurringCostsScreen(recurringCosts, onLedgerJump)
            RecurringSide.INCOMES -> RecurringIncomesScreen(recurringIncomes, onLedgerJump)
        }
    }
}

@Composable
private fun SideButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(label, fontWeight = FontWeight.Medium)
        }
    }
}
