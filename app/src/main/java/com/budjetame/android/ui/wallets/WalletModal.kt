package com.budjetame.android.ui.wallets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.budjetame.android.R
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.ui.common.AccountLocaleResources
import com.budjetame.android.ui.common.FreezeSection
import com.budjetame.android.ui.common.LocalDisplayLocale
import com.budjetame.android.ui.common.UnfreezeSection
import com.budjetame.android.ui.common.fieldErrorSupportingText
import com.budjetame.android.ui.common.localized
import com.budjetame.android.util.Money

/**
 * The create/edit/freeze Wallet form inside an AlertDialog (web issue #49).
 * Create and edit share one modal: Type and Opening balance appear only while
 * creating, and the tap-again freeze confirmation only while editing. Also
 * hosts the Transaction form's inline "New wallet…" creation (ADR-0013):
 * `allowedTypes` restricts the create form's Type selector — an Income's
 * Wallet field never creates a Contact Wallet (ADR-0017) — and edit mode
 * never shows the selector, so the lock is create-only.
 *
 * Validation is submit-and-validate (ADR-0009): Save is disabled only while
 * a save or freeze is in flight, and the host's last Save attempt renders
 * its Field Errors inline under Name and Opening balance — a server
 * rejection stays in the banner below the fields.
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
    onUnfreeze: () -> Unit,
    onClose: () -> Unit,
) {
    val locale = LocalDisplayLocale.current
    val wallet = modal.wallet
    val editing = wallet != null
    val nameError = modal.fieldErrors[WalletFields.NAME]
    val openingBalanceError = modal.fieldErrors[WalletFields.OPENING_BALANCE]

    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        title = localized {
            Text(stringResource(if (editing) R.string.wallet_form_title_edit else R.string.wallet_form_title_new))
        },
        text = localized {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (wallet != null) {
                    Text(
                        text = stringResource(R.string.wallet_form_type_locked, stringResource(walletTypeLabel(wallet.type))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (wallet?.frozen == true) {
                    Text(
                        text = modal.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = modal.name,
                        onValueChange = onNameChange,
                        label = { Text(stringResource(R.string.wallet_form_name)) },
                        placeholder = { Text(stringResource(R.string.wallet_form_name_placeholder)) },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = fieldErrorSupportingText(nameError),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag("wallet-name"),
                    )
                }

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
                        val labels = types.map { stringResource(walletTypeLabel(it)) }
                        Text(
                            text = stringResource(R.string.wallet_form_types_fixed, labels.joinToString(", ")),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    OutlinedTextField(
                        value = modal.openingBalance,
                        onValueChange = onOpeningBalanceChange,
                        label = { Text(stringResource(R.string.wallet_form_opening_balance)) },
                        placeholder = { Text(stringResource(R.string.wallet_form_amount_placeholder)) },
                        enabled = modal.type != WalletType.CONTACT,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        isError = openingBalanceError != null,
                        supportingText = fieldErrorSupportingText(openingBalanceError),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag("wallet-opening-balance"),
                    )
                    Text(
                        text = if (modal.type == WalletType.CONTACT) {
                            stringResource(R.string.wallet_form_opening_balance_contact)
                        } else {
                            stringResource(R.string.wallet_form_opening_balance_hint, Money.formatEuros("0.00", locale))
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
                    if (wallet.frozen) {
                        UnfreezeSection(
                            isFreezing = modal.freezing,
                            onUnfreeze = onUnfreeze,
                            heading = stringResource(R.string.wallet_form_unfreeze),
                            description = stringResource(R.string.wallet_form_unfreeze_description),
                        )
                    } else {
                        FreezeSection(
                            canFreeze = modal.canFreeze,
                            isFreezing = modal.freezing,
                            confirmingFreeze = modal.confirmingFreeze,
                            freezeError = modal.freezeError,
                            onFreeze = onFreeze,
                            disabledLabel = if (!modal.canFreeze) {
                                stringResource(
                                    R.string.wallet_form_freeze_balance,
                                    Money.formatEuros("0.00", locale),
                                    Money.formatEuros(wallet.balance, locale),
                                )
                            } else {
                                null
                            },
                            heading = stringResource(R.string.wallet_form_freeze),
                            description = stringResource(R.string.wallet_form_freeze_description, Money.formatEuros("0.00", locale)),
                        )
                    }
                }
            }
        },
        // Frozen wallets: no Save button, only Unfreeze + Cancel (ADR-0028).
        confirmButton = if (wallet?.frozen == true) {
            {}
        } else {
            localized {
                Button(onClick = onSubmit, enabled = !modal.busy, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(
                        stringResource(
                            when {
                                modal.submitting -> R.string.common_saving
                                editing -> R.string.common_save
                                else -> R.string.wallet_form_create
                            },
                        ),
                    )
                }
            }
        },
        dismissButton = localized {
            TextButton(onClick = onClose, enabled = !modal.busy) {
                Text(stringResource(R.string.common_cancel))
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
            value = stringResource(walletTypeLabel(value)),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.wallet_form_type)) },
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
            // The menu is its own window: re-apply the Account Locale (ADR-0010).
            AccountLocaleResources {
                WalletType.entries.filter { it in types }.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(stringResource(walletTypeLabel(type))) },
                        onClick = {
                            onSelect(type)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
