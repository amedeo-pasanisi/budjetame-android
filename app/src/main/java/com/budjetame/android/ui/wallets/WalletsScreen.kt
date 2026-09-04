package com.budjetame.android.ui.wallets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.RowEditButton
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.ui.theme.Slate500
import com.budjetame.android.ui.theme.Slate700
import com.budjetame.android.util.Money

/**
 * The Wallets tab (ticket #15, ADR-0004 anatomy): four fixed sections —
 * Contacts, Checking Accounts, Credit Cards, Cash — each sorted A→Z
 * case-insensitively with signed balances, plus a collapsed Frozen Wallets
 * footer. A row is a tap surface with sibling trailing buttons inside one
 * card (web issue #93): the whole surface (name + type + balance, on
 * active AND frozen rows) sends the ledger jump — the Transactions tab
 * opens pre-filtered to that Wallet, a frozen one with the read-only
 * banner already showing — and the trailing ✎ opens the edit modal
 * (rename/freeze); frozen rows add one-tap Unfreeze beside it. The old
 * whole-row edit and whole-row unfreeze semantics moved here.
 */
@Composable
fun WalletsScreen(
    wallets: WalletGateway,
    /** Send a ledger jump (ADR-0004): open the Transactions tab with the
     * ledger pre-filtered to one Wallet. Fired by the whole-row tap surface
     * (web issue #93). */
    onLedgerJump: (LedgerJump) -> Unit = {},
) {
    val viewModel: WalletsViewModel = viewModel { WalletsViewModel(wallets) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        WalletsHeader(onNewWallet = viewModel::openCreate)

        val loadError = state.loadError
        when {
            state.loading -> MessageBody(
                text = "Loading wallets…",
                modifier = Modifier.weight(1f),
            )
            loadError != null -> LoadErrorBody(
                message = loadError,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f),
            )
            state.wallets.isEmpty() -> MessageBody(
                text = "No wallets yet. Add your first one to start tracking.",
                modifier = Modifier.weight(1f),
            )
            else -> WalletsList(state = state, viewModel = viewModel, onLedgerJump = onLedgerJump, modifier = Modifier.weight(1f))
        }
    }

    state.modal?.let { modal ->
        WalletModal(
            modal = modal,
            onNameChange = viewModel::onNameChange,
            onTypeChange = viewModel::onTypeChange,
            onOpeningBalanceChange = viewModel::onOpeningBalanceChange,
            onSubmit = viewModel::submit,
            onFreeze = viewModel::onFreezeTap,
            onClose = viewModel::closeModal,
        )
    }
}

@Composable
private fun WalletsHeader(onNewWallet: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Wallets",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onNewWallet,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text("New wallet")
        }
    }
}

@Composable
private fun WalletsList(
    state: WalletsViewModel.UiState,
    viewModel: WalletsViewModel,
    onLedgerJump: (LedgerJump) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleSections = state.sections.filter { it.items.isNotEmpty() }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        visibleSections.forEach { section ->
            item(key = "section-${section.type}") {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate700,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(section.items, key = { it.id }) { wallet ->
                WalletCard(
                    name = wallet.name,
                    subtitle = walletTypeLabel(wallet.type),
                    balance = wallet.balance,
                    onOpenLedger = { onLedgerJump(LedgerJump.Wallet(wallet.id)) },
                    actions = {
                        RowEditButton(name = wallet.name, onEdit = { viewModel.openEdit(wallet) })
                    },
                )
            }
        }
        if (state.frozenWallets.isNotEmpty()) {
            item(key = "frozen") {
                FrozenSection(
                    frozenWallets = state.frozenWallets,
                    expanded = state.frozenExpanded,
                    unfreezeError = state.unfreezeError,
                    onToggle = viewModel::toggleFrozenExpanded,
                    onUnfreeze = viewModel::unfreeze,
                    onEdit = viewModel::openEdit,
                    onOpenLedger = onLedgerJump,
                )
            }
        }
    }
}

@Composable
private fun FrozenSection(
    frozenWallets: List<WalletDto>,
    expanded: Boolean,
    unfreezeError: String?,
    onToggle: () -> Unit,
    onUnfreeze: (WalletDto) -> Unit,
    onEdit: (WalletDto) -> Unit,
    onOpenLedger: (LedgerJump) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text("Frozen wallets (${frozenWallets.size})")
        }
        if (expanded) {
            frozenWallets.forEach { wallet ->
                WalletCard(
                    name = wallet.name,
                    subtitle = "${walletTypeLabel(wallet.type)} · Frozen",
                    balance = wallet.balance,
                    onOpenLedger = { onOpenLedger(LedgerJump.Wallet(wallet.id)) },
                    actions = {
                        TextButton(
                            onClick = { onUnfreeze(wallet) },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            // The web's Unfreeze: text-xs font-medium in
                            // indigo — 12 sp Medium, not the M3 14 sp
                            // label (ticket #44).
                            Text("Unfreeze", style = MaterialTheme.typography.labelMedium)
                        }
                        RowEditButton(name = wallet.name, onEdit = { onEdit(wallet) })
                    },
                )
            }
            unfreezeError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * One Wallet row's card (ADR-0004 anatomy): the tap surface (name + type +
 * balance) sends the ledger jump; the trailing actions — the ✎ Edit button,
 * and on a frozen row the Unfreeze text button beside it — are its
 * siblings, never nested inside the tap surface.
 */
@Composable
private fun WalletCard(
    name: String,
    subtitle: String,
    balance: String,
    onOpenLedger: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
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
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(shape),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenLedger)
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Slate500,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = Money.formatSignedEuros(balance),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            actions()
        }
    }
}

