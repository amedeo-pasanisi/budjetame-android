package com.budjetame.android.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.util.Money

/**
 * The create/edit/freeze Wallet form inside an AlertDialog (web issue #49).
 * Create and edit share one modal: Type and Opening balance appear only while
 * creating, and the tap-again freeze confirmation only while editing. Also
 * hosts the Transaction form's inline "New wallet…" creation (ADR-0013):
 * `allowedTypes` restricts the create form's Type selector — an Income's
 * Wallet field never creates a Contact Wallet (ADR-0017) — and edit mode
 * never shows the selector, so the lock is create-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletModal(
    modal: WalletModalState,
    allowedTypes: Set<WalletType>? = null,
    onNameChange: (String) -> Unit,
    onTypeChange: (WalletType) -> Unit,
    onOpeningBalanceChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFreeze: () -> Unit,
    onClose: () -> Unit,
) {
    val wallet = modal.wallet
    val editing = wallet != null

    AlertDialog(
        onDismissRequest = { if (!modal.submitting && !modal.freezing) onClose() },
        title = { Text(if (editing) "Edit wallet" else "New wallet") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (wallet != null) {
                    Text(
                        text = "${walletTypeLabel(wallet.type)} · type cannot be changed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = modal.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Intesa checking") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("wallet-name"),
                )

                if (!editing) {
                    WalletTypeField(
                        value = modal.type,
                        types = allowedTypes ?: WalletType.entries.toSet(),
                        onSelect = onTypeChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    allowedTypes?.let { types ->
                        Text(
                            text = types.joinToString(", ") { walletTypeLabel(it) } +
                                " · fixed for this form",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    OutlinedTextField(
                        value = modal.openingBalance,
                        onValueChange = onOpeningBalanceChange,
                        label = { Text("Opening balance (optional)") },
                        placeholder = { Text("0.00") },
                        enabled = modal.type != WalletType.CONTACT,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    Text(
                        text = if (modal.type == WalletType.CONTACT) {
                            "Contact wallets start at €0 — money moves only through transfers."
                        } else {
                            "Money you already have. Defaults to €0.00."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                modal.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                if (wallet != null) {
                    FreezeSection(modal = modal, wallet = wallet, onFreeze = onFreeze)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = modal.canSubmit, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(
                    when {
                        modal.submitting -> "Saving…"
                        editing -> "Save"
                        else -> "Create wallet"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !modal.submitting && !modal.freezing) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletTypeField(
    value: WalletType,
    types: Set<WalletType>,
    onSelect: (WalletType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = walletTypeLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .testTag("wallet-type"),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            WalletType.entries.filter { it in types }.forEach { type ->
                DropdownMenuItem(
                    text = { Text(walletTypeLabel(type)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun FreezeSection(
    modal: WalletModalState,
    wallet: WalletDto,
    onFreeze: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = "Freeze wallet",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = "Hides the wallet and makes it read-only. Only possible at €0.00 balance; its transactions stay visible.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    modal.freezeError?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Button(
        onClick = onFreeze,
        enabled = modal.canFreeze && !modal.freezing && !modal.submitting,
        colors = if (modal.confirmingFreeze) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Text(
            when {
                modal.freezing -> "Freezing…"
                !modal.canFreeze -> "Freeze requires €0.00 balance (currently ${Money.formatEuros(wallet.balance)})"
                modal.confirmingFreeze -> "Tap again to confirm freeze"
                else -> "Freeze wallet"
            },
        )
    }
}
