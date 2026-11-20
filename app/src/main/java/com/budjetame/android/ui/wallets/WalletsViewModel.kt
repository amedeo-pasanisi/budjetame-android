package com.budjetame.android.ui.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.R
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.common.FieldErrors
import com.budjetame.android.ui.common.UiText
import com.budjetame.android.ui.common.amountFieldError
import com.budjetame.android.ui.common.apiErrorMessage
import com.budjetame.android.ui.common.parseAmountInput
import com.budjetame.android.ui.undo.DeletedEntity
import com.budjetame.android.ui.undo.UndoSink
import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The create/edit/freeze/delete Wallet modal's draft (null = modal closed). Shared
 * by every host of the modal: the Wallets screen (ticket #15) and the
 * Transaction form's inline "New wallet…" creation (ADR-0013, ticket #21) —
 * one draft type, so the create form cannot drift between its hosts.
 */
data class WalletModalState(
    val wallet: WalletDto? = null,
    val name: String = "",
    val type: WalletType = WalletType.CHECKING,
    val openingBalance: String = "",
    /** The server's rejection — the form-level banner, never mixed with
     * [fieldErrors]. */
    val error: UiText? = null,
    /** The last Save attempt's Field Errors (ADR-0009), keyed by
     * [WalletFields]: set only by a host's submit, never cleared by typing. */
    val fieldErrors: FieldErrors = emptyMap(),
    val submitting: Boolean = false,
    val confirmingFreeze: Boolean = false,
    val freezing: Boolean = false,
    val freezeError: UiText? = null,
    /** True while a Frozen Wallet's delete is in flight (web ADR-0034). */
    val deleting: Boolean = false,
    /** Why the last delete failed — the backend's rule ("This wallet has 12
     * transactions") through the error catalogue, or the generic line. */
    val deleteError: UiText? = null,
) {
    val editing: Boolean get() = wallet != null

    /** Submit-and-validate (ADR-0009): Save is disabled by in-flight work
     * only — a save, a freeze or a delete — never by validation. */
    val busy: Boolean get() = submitting || freezing || deleting

    /** Freeze only when the balance is exactly €0 (ADR-0002). */
    val canFreeze: Boolean
        get() = editing && wallet?.let { BigDecimal(it.balance).compareTo(BigDecimal.ZERO) == 0 } == true
}

/** The Wallet form's Field Error keys (ADR-0009): one per field a message
 * can render under. */
object WalletFields {
    const val NAME = "name"
    const val OPENING_BALANCE = "openingBalance"
}

/**
 * Submit-and-validate (ADR-0009, mirror of the web WalletForm's
 * `validate()`): one Field Error per wrong field of [form], empty when it
 * may save. Name is required in create and edit. The Opening balance
 * (create only) is an optional tolerant Amount Input: blank stays valid —
 * the Wallet starts at €0 — while a typed value must be a positive amount,
 * so an explicit "0" is rejected like the web's (leave it blank instead);
 * a Contact Wallet's disabled field is never judged. Shared by every host of the Wallet modal, so the rule cannot
 * drift between the Wallets screen and the inline creates (ADR-0013).
 */
fun validateWalletForm(form: WalletModalState): FieldErrors = buildMap {
    if (form.name.isBlank()) put(WalletFields.NAME, UiText.of(R.string.field_error_name_empty))
    if (!form.editing && form.type != WalletType.CONTACT && form.openingBalance.isNotBlank()) {
        amountFieldError(form.openingBalance)?.let { put(WalletFields.OPENING_BALANCE, it) }
    }
}

/**
 * The opening balance to send for a create draft that passed
 * [validateWalletForm]: "0.00" for a Contact Wallet or a blank field (no
 * Opening Balance Transaction), otherwise the tolerant parser's canonical
 * value — never the typed text.
 */
fun walletOpeningBalanceToSend(form: WalletModalState): String =
    if (form.type == WalletType.CONTACT || form.openingBalance.isBlank()) {
        "0.00"
    } else {
        parseAmountInput(form.openingBalance)!!.toPlainString()
    }

/**
 * The Wallets screen's state machine (ticket #15), ported from the web app's
 * WalletsScreen + WalletForm: load with sections, create/rename/freeze,
 * one-tap unfreeze, and the one-tap delete of a Frozen Wallet whose Undo
 * entry goes to the shell's [undoSink] (web ADR-0034) — with the web's
 * exact error strings. Data is refetched in
 * the background when the global data version bumps (ADR-0002).
 */
class WalletsViewModel(
    private val wallets: WalletGateway,
    private val undoSink: UndoSink = UndoSink.None,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: UiText? = null,
        val wallets: List<WalletDto> = emptyList(),
        val frozenExpanded: Boolean = false,
        val unfreezeError: UiText? = null,
        val modal: WalletModalState? = null,
    ) {
        val sections: List<WalletSection> get() = walletSections(wallets)
        val frozenWallets: List<WalletDto> get() = frozenWalletsOf(wallets)
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        // ADR-0002: the transport bumps the data version after every write, and
        // this screen refetches in the background. The first emission (the
        // current version) is the initial load.
        viewModelScope.launch {
            DataVersion.version.collect { reload() }
        }
    }

    fun openCreate() {
        _uiState.update { it.copy(modal = WalletModalState(), unfreezeError = null) }
    }

    fun openEdit(wallet: WalletDto) {
        _uiState.update {
            it.copy(modal = WalletModalState(wallet = wallet, name = wallet.name), unfreezeError = null)
        }
    }

    fun closeModal() {
        _uiState.update { it.copy(modal = null) }
    }

    fun unfreezeFromModal() {
        val modal = _uiState.value.modal ?: return
        if (modal.busy) return
        val wallet = modal.wallet ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(unfreezeError = null) }
            try {
                val unfrozen = wallets.unfreezeWallet(wallet.id)
                _uiState.update { state ->
                    state.copy(
                        wallets = state.wallets.map { existing ->
                            if (existing.id == unfrozen.id) unfrozen else existing
                        },
                        modal = null,
                    )
                }
            } catch (_: Exception) {
                _uiState.update { state ->
                    state.copy(unfreezeError = UiText.of(R.string.errors_wallets_unfreeze))
                }
            }
        }
    }

    fun onNameChange(value: String) = updateModal { it.copy(name = value, error = null) }

    fun onTypeChange(value: WalletType) = updateModal {
        if (value == WalletType.CONTACT) {
            // A Contact wallet starts at €0: drop any drafted amount.
            it.copy(type = value, openingBalance = "", error = null)
        } else {
            it.copy(type = value, error = null)
        }
    }

    fun onOpeningBalanceChange(value: String) =
        updateModal { it.copy(openingBalance = value, error = null) }

    /**
     * Submit-and-validate (ADR-0009): validate first — any Field Error is
     * stored on the modal and nothing is written; a valid form clears them
     * and saves exactly as before. Only the next Save refreshes the errors.
     */
    fun submit() {
        val modal = _uiState.value.modal ?: return
        if (modal.busy) return
        val fieldErrors = validateWalletForm(modal)
        updateModal { it.copy(fieldErrors = fieldErrors) }
        if (fieldErrors.isNotEmpty()) return
        if (modal.editing) rename(modal) else create(modal)
    }

    fun onFreezeTap() {
        val modal = _uiState.value.modal ?: return
        if (!modal.canFreeze) return
        if (!modal.confirmingFreeze) {
            updateModal { it.copy(confirmingFreeze = true, freezeError = null) }
            return
        }
        val wallet = modal.wallet ?: return
        viewModelScope.launch {
            updateModal { it.copy(freezing = true, freezeError = null) }
            try {
                wallets.freezeWallet(wallet.id)
                _uiState.update { state ->
                    state.copy(
                        wallets = state.wallets.map { existing ->
                            if (existing.id == wallet.id) existing.copy(frozen = true) else existing
                        },
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        confirmingFreeze = false,
                        freezing = false,
                        freezeError = if (error.status == 422) {
                            UiText.of(R.string.errors_wallets_freeze_balance)
                        } else {
                            UiText.of(R.string.errors_wallets_freeze)
                        },
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(confirmingFreeze = false, freezing = false, freezeError = UiText.of(R.string.errors_wallets_freeze))
                }
            }
        }
    }

    /**
     * Delete the modal's Frozen Wallet (web ADR-0034): one tap, no confirm
     * step — the Undo entry is the confirmation. The button is always
     * tappable; the backend refuses a Wallet with history and the modal
     * shows its reason inline. A success drops the Wallet from the list,
     * closes the modal and hands the deleted row to the Undo stack.
     */
    fun onDeleteTap() {
        val modal = _uiState.value.modal ?: return
        val wallet = modal.wallet ?: return
        if (modal.busy || !wallet.frozen) return
        viewModelScope.launch {
            updateModal { it.copy(deleting = true, deleteError = null) }
            try {
                val deleted = wallets.deleteWallet(wallet.id)
                undoSink.pushDeleted(DeletedEntity.Wallet(deleted))
                _uiState.update { state ->
                    state.copy(
                        wallets = state.wallets.filterNot { it.id == wallet.id },
                        // Only this Wallet's modal closes: one opened on
                        // another row meanwhile stays.
                        modal = state.modal?.takeUnless { it.wallet?.id == wallet.id },
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        deleting = false,
                        // A delete has no name to conflict on: any status
                        // but a cataloged 422 rule is the generic line.
                        deleteError = apiErrorMessage(
                            error,
                            UiText.of(R.string.errors_wallets_delete),
                            UiText.of(R.string.errors_wallets_delete),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(deleting = false, deleteError = UiText.of(R.string.errors_wallets_delete)) }
            }
        }
    }

    fun toggleFrozenExpanded() {
        _uiState.update { it.copy(frozenExpanded = !it.frozenExpanded, unfreezeError = null) }
    }

    fun unfreeze(wallet: WalletDto) {
        viewModelScope.launch {
            _uiState.update { it.copy(unfreezeError = null) }
            try {
                val unfrozen = wallets.unfreezeWallet(wallet.id)
                _uiState.update { state ->
                    state.copy(
                        wallets = state.wallets.map { existing ->
                            if (existing.id == unfrozen.id) unfrozen else existing
                        },
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(unfreezeError = UiText.of(R.string.errors_wallets_unfreeze)) }
            }
        }
    }

    fun retry() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadError = null) }
            reload()
        }
    }

    private fun create(modal: WalletModalState) {
        val openingBalance = walletOpeningBalanceToSend(modal)
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val created = wallets.createWallet(modal.name.trim(), modal.type, openingBalance)
                _uiState.update { state ->
                    state.copy(wallets = state.wallets + created, modal = null)
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error,
                            UiText.of(R.string.errors_wallets_conflict),
                            UiText.of(R.string.errors_wallets_create),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = UiText.of(R.string.errors_wallets_create)) }
            }
        }
    }

    private fun rename(modal: WalletModalState) {
        val wallet = modal.wallet ?: return
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val renamed = wallets.renameWallet(wallet.id, modal.name.trim())
                _uiState.update { state ->
                    state.copy(
                        wallets = state.wallets.map { existing ->
                            if (existing.id == renamed.id) renamed else existing
                        },
                        modal = null,
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error,
                            UiText.of(R.string.errors_wallets_conflict),
                            UiText.of(R.string.errors_wallets_rename),
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = UiText.of(R.string.errors_wallets_rename)) }
            }
        }
    }

    /**
     * Fetch the list. A failed background refetch keeps the held data on
     * screen (ADR-0002); a failure with nothing to show surfaces the error.
     */
    private suspend fun reload() {
        try {
            val loaded = wallets.fetchWallets()
            _uiState.update { it.copy(wallets = loaded, loadError = null, loading = false) }
        } catch (_: Exception) {
            _uiState.update { state ->
                if (state.wallets.isEmpty()) {
                    state.copy(loadError = UiText.of(R.string.errors_wallets_load), loading = false)
                } else {
                    state.copy(loading = false)
                }
            }
        }
    }

    private fun updateModal(transform: (WalletModalState) -> WalletModalState) {
        _uiState.update { state ->
            state.modal?.let { state.copy(modal = transform(it)) } ?: state
        }
    }
}
