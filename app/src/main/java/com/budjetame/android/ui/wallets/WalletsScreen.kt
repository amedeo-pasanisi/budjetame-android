package com.budjetame.android.ui.wallets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.util.Money

/**
 * The Wallets tab (ticket #15): four fixed sections — Contacts, Checking
 * Accounts, Credit Cards, Cash — each sorted A→Z case-insensitively with
 * signed balances, plus a collapsed Frozen Wallets footer with one-tap
 * unfreeze. Create, rename, and freeze live in a shared modal.
 */
@Composable
fun WalletsScreen(wallets: WalletGateway) {
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
            else -> WalletsList(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
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
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onNewWallet) {
            Text("New wallet")
        }
    }
}

@Composable
private fun WalletsList(
    state: WalletsViewModel.UiState,
    viewModel: WalletsViewModel,
    modifier: Modifier = Modifier,
) {
    val visibleSections = state.sections.filter { it.items.isNotEmpty() }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        visibleSections.forEach { section ->
            item(key = "section-${section.type}") {
                Text(
                    text = section.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            items(section.items, key = { it.id }) { wallet ->
                WalletRow(
                    name = wallet.name,
                    subtitle = walletTypeLabel(wallet.type),
                    balance = wallet.balance,
                    onClick = { viewModel.openEdit(wallet) },
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
) {
    Column(modifier = Modifier.padding(top = 16.dp)) {
        OutlinedButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text("Frozen wallets (${frozenWallets.size})")
        }
        if (expanded) {
            frozenWallets.forEach { wallet ->
                WalletRow(
                    name = wallet.name,
                    subtitle = "${walletTypeLabel(wallet.type)} · Frozen",
                    balance = wallet.balance,
                    onClick = { onUnfreeze(wallet) },
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

@Composable
private fun WalletRow(
    name: String,
    subtitle: String,
    balance: String,
    onClick: () -> Unit,
) {
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = Money.formatSignedEuros(balance),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun MessageBody(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadErrorBody(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
            Text("Retry")
        }
    }
}
