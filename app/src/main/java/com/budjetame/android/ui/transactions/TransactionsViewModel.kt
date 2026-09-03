package com.budjetame.android.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.api.apiErrorMessage
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.transaction.latLngFromWire
import com.budjetame.android.data.transaction.placeFromWire
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.categories.CategoryModalState
import com.budjetame.android.ui.wallets.WalletModalState
import com.budjetame.android.ui.wallets.normalizeOpeningBalance
import com.budjetame.android.util.Dates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Recurring definition filter's kinds (web issue #86): the filter
 * bar's one select lists every Recurring Cost and every Recurring Income
 * (grouped under kind captions — names may collide across kinds), so a
 * pick names its kind alongside the definition's id.
 */
enum class RecurringFilterKind { COST, INCOME }

/**
 * A picked definition in the Recurring filter (web issue #86): the kind
 * says which definition list the id belongs to and which wire key it
 * travels under — recurring_cost_id for a Recurring Cost,
 * recurring_income_id for a Recurring Income — so a cost and an income
 * that share an id can never be confused, and the single pick always
 * sends at most one key.
 */
data class RecurringFilter(val kind: RecurringFilterKind, val id: Int)

/**
 * The Transactions ledger's state machine (ticket #19), ported from the web
 * app's TransactionsScreen read path: a newest-first list with cursor paging
 * (50 per page) and infinite scroll, the collapsible filter bar (wallet —
 * frozen ones included — date range, category, recurring definition (web
 * issue #86, ticket #25)), and the debounced description search — any
 * filter or search change refetches the first page with everything
 * applied, and further pages keep the filters and hand the opaque cursor
 * back verbatim. Data is refetched in the background when the global data
 * version bumps (ADR-0002).
 */
class TransactionsViewModel(
    private val transactions: TransactionGateway,
    private val wallets: WalletGateway,
    private val categories: CategoryGateway,
    private val recurringCosts: RecurringCostGateway,
    private val recurringIncomes: RecurringIncomeGateway,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
    /** The device GPS (ticket #29): the form's "Use my location" pick and
     * its first-save prefill. The permission *prompt* is a screen concern —
     * the ViewModel asks by raising the modal's
     * `requestingLocationPermission` flag, the screen launches the system
     * dialog, and reports the answer back through
     * `onLocationPermissionResult`. */
    private val location: DeviceLocation,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val wallets: List<WalletDto> = emptyList(),
        val categories: List<CategoryDto> = emptyList(),
        /** The Recurring Cost definitions the filter bar's Recurring select
         * and the Expense form's link picker offer (web issues #86/#57) —
         * fetched on every reload, never required by the ledger itself (an
         * empty or failed list never blocks it). */
        val recurringCosts: List<RecurringCostDto> = emptyList(),
        /** The Recurring Income definitions the filter bar's Recurring
         * select and the Income form's link picker offer (web issues
         * #86/#61), the mirror — fetched on every reload, never required by
         * the ledger itself. */
        val recurringIncomes: List<RecurringIncomeDto> = emptyList(),
        val transactions: List<TransactionDto> = emptyList(),
        val nextCursor: String? = null,
        val loadingMore: Boolean = false,
        val loadMoreError: String? = null,
        val ledgerEmpty: Boolean = false,
        val filtersOpen: Boolean = false,
        val filterWalletId: Int? = null,
        val filterFromDate: String? = null,
        val filterToDate: String? = null,
        val filterCategoryId: Int? = null,
        /** The Recurring definition filter (web issue #86, ticket #25): a
         * pick narrows the ledger to the Transactions linked to exactly
         * that Recurring Cost or Recurring Income; null = all. */
        val filterRecurring: RecurringFilter? = null,
        val search: String = "",
        val searchNeedle: String = "",
        val savedWarning: String? = null,
        /** True while the export request is in flight — the chips line's and
         * the panel footer's Export to Excel buttons show "Exporting…" and a
         * second press cannot fire a concurrent request (web parity: one
         * file per press). */
        val exporting: Boolean = false,
        /** A failed export's message (the web screen's export error line),
         * null = no failure. Cleared by the next Export press. */
        val exportError: String? = null,
        /** The fetched workbook, waiting for the screen to hand it to the
         * system share sheet (ticket #28). The screen shares it once and
         * reports back through `onExportHandled`, which clears it — a file
         * never leaves the app twice. */
        val exportFile: ExportFile? = null,
        val modal: ModalState? = null,
        /** The inline "New wallet…" modal stacked on the Transaction form
         * (ADR-0013), null = closed. */
        val walletCreate: WalletCreateState? = null,
        /** The inline "New category…" modal stacked on the Transaction form
         * (ADR-0013), null = closed. */
        val categoryCreate: CategoryCreateState? = null,
    ) {
        /** True when any Filters-bar field is set — the search needle is
         * separate, it rides along with the bar's fields (ADR-0009). */
        val filtersActive: Boolean
            get() = filterWalletId != null || filterFromDate != null ||
                filterToDate != null || filterCategoryId != null || filterRecurring != null

        /** The Wallet the Wallet filter selects, for the frozen banner. */
        val selectedWallet: WalletDto?
            get() = wallets.find { it.id == filterWalletId }

        /** The empty-list message the screen shows, exactly like the web. */
        val emptyMessage: String
            get() = when {
                searchNeedle.isNotEmpty() -> "No transactions match your search."
                filtersActive -> "No transactions match these filters."
                else -> "Nothing here yet."
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /**
     * The create/edit/delete Transaction form's draft (null = modal closed).
     * Create and edit share one modal: the Type selector appears only while
     * creating (type is immutable once recorded), and the tap-again delete
     * confirmation only while editing. `recurringCostId` is the Expense
     * form's Recurring Cost link pick — null = none (web issue #57);
     * `recurringIncomeId` is the Income form's Recurring Income link pick,
     * the mirror (web issue #61).
     */
    data class ModalState(
        val editing: TransactionDto? = null,
        val type: TransactionType = TransactionType.EXPENSE,
        val amount: String = "",
        val date: String = "",
        val walletId: Int? = null,
        val sourceWalletId: Int? = null,
        val destinationWalletId: Int? = null,
        val categoryId: Int? = null,
        val recurringCostId: Int? = null,
        val recurringIncomeId: Int? = null,
        val description: String = "",
        /** The Geographic Location's coordinates (ticket #29), null = none. */
        val location: LatLng? = null,
        /** The optional Place reference (ADR-0005): set by a pick that
         * carries one (a Google search pick or POI tap), cleared by a
         * coordinates-only pick (free-map tap, GPS) or a Remove. It always
         * accompanies coordinates — never the reverse. */
        val place: Place? = null,
        /** Set once the user changed the location themselves (a pick, GPS,
         * or Remove): a pending GPS prefill can never overwrite an explicit
         * choice. */
        val locationTouched: Boolean = false,
        /** Set once the user removes the location from a new Transaction:
         * the first-save prompt must not silently re-attach a position the
         * user opted out of. Seeded from the session flag so the opt-out
         * survives the modal being closed and reopened; a fresh ViewModel
         * (a new app session) clears it and the prefill returns. */
        val locationOptedOut: Boolean = false,
        /** True while the "Use my location" lookup runs, so the button can
         * disable and read "Locating…" instead of failing silently. */
        val locating: Boolean = false,
        /** Inline GPS failure message (denied, timeout, unavailable),
         * cleared by a successful GPS pick, a map pick, or a Remove. */
        val gpsError: String? = null,
        /** True while the map picker dialog is open (the seam content). */
        val showingPicker: Boolean = false,
        /** True while the platform location-permission prompt is pending:
         * the screen watches this flag, launches the system request, and
         * reports the answer back through `onLocationPermissionResult`. */
        val requestingLocationPermission: Boolean = false,
        val error: String? = null,
        val submitting: Boolean = false,
        val confirmingDelete: Boolean = false,
        val deleting: Boolean = false,
    ) {
        val isEditing: Boolean get() = editing != null

        val isTransfer: Boolean get() = type == TransactionType.TRANSFER

        val busy: Boolean get() = submitting || deleting

        /** Mandatory fields gate Save: a strictly positive amount and the
         * Wallet(s) the type needs — a Transfer also needs two distinct
         * Wallets. The date defaults to today in Europe/Rome and cannot be
         * cleared, so it is always set. */
        val canSubmit: Boolean
            get() {
                if (busy || parseAmount(amount) == null) return false
                return if (isTransfer) {
                    sourceWalletId != null &&
                        destinationWalletId != null &&
                        sourceWalletId != destinationWalletId
                } else {
                    walletId != null
                }
            }
    }

    /**
     * The inline "New wallet…" modal (ADR-0013): a create-only
     * WalletModalState draft plus the exact Wallet field whose sentinel was
     * picked — so the created Wallet is auto-selected into it — and the
     * eligibility lock (the Wallet types the originating field may create,
     * ADR-0017); null = unrestricted.
     */
    data class WalletCreateState(
        val target: WalletFieldTarget,
        val allowedTypes: Set<WalletType>?,
        val modal: WalletModalState,
    )

    /**
     * The inline "New category…" modal (ADR-0013): a create-only
     * CategoryModalState draft whose type is locked to the form's type —
     * Expense for an Expense, Income for an Income — so the created
     * Category always fits the Transaction being recorded.
     */
    data class CategoryCreateState(
        val lockedType: CategoryType,
        val modal: CategoryModalState,
    )

    /**
     * Monotonic reset counter: every reload (filter/search change, version
     * bump) starts a new generation, and a page fetch from an earlier
     * generation is discarded on arrival — so a further page still in
     * flight when a reset happens never appends its pre-reset rows.
     */
    private var generation = 0

    private var searchDebounceJob: Job? = null

    /** The session's location opt-out (web issue #25, ticket #29): once the
     * user removes a location from a new Transaction, the GPS prefill stays
     * off for the rest of the ViewModel's life (the app session) — a fresh
     * session clears it and the prefill returns. The modal state survives
     * tab switches in the ViewModel, so unlike the web's sessionStorage no
     * storage is needed for the form's own lifetime; the flag covers the
     * next form the user opens. */
    private var sessionLocationOptedOut = false

    /** The in-flight location-permission prompt (ticket #29): one request at
     * a time — the screen launches the system dialog while the modal's
     * `requestingLocationPermission` flag is up and completes this with the
     * user's answer. */
    private var pendingLocationPermission: CompletableDeferred<Boolean>? = null

    init {
        // ADR-0002: the transport bumps the data version after every write,
        // and this screen refetches in the background. The first emission
        // (the current version) is the initial load.
        viewModelScope.launch {
            DataVersion.version.collect { reload() }
        }
    }

    fun toggleFilters() {
        _uiState.update { it.copy(filtersOpen = !it.filtersOpen) }
    }

    fun onFilterWalletChange(walletId: Int?) {
        if (_uiState.value.filterWalletId == walletId) return
        changeFilters { it.copy(filterWalletId = walletId) }
    }

    fun onFilterFromDateChange(date: String?) {
        if (_uiState.value.filterFromDate == date) return
        changeFilters { it.copy(filterFromDate = date) }
    }

    fun onFilterToDateChange(date: String?) {
        if (_uiState.value.filterToDate == date) return
        changeFilters { it.copy(filterToDate = date) }
    }

    fun onFilterCategoryChange(categoryId: Int?) {
        if (_uiState.value.filterCategoryId == categoryId) return
        changeFilters { it.copy(filterCategoryId = categoryId) }
    }

    /**
     * The Recurring definition filter (web issue #86, ticket #25): null =
     * all; a pick narrows the ledger to the Transactions linked to exactly
     * that definition, replacing any previous pick (the bar's one select).
     * An unchanged selection never refetches.
     */
    fun onFilterRecurringChange(selection: RecurringFilter?) {
        if (_uiState.value.filterRecurring == selection) return
        changeFilters { it.copy(filterRecurring = selection) }
    }

    // --- Filtered-line and panel-footer clear actions (web issue #92, ticket #35) ---

    /**
     * The merged date-range chip's ✕ (web issue #92): one chip, one ✕, one
     * date-range filter — both bounds reset together in a single refetch,
     * like the web's one batched state update (two separate clears would
     * fetch twice). Nothing set: a no-op.
     */
    fun clearFilterDates() {
        val state = _uiState.value
        if (state.filterFromDate == null && state.filterToDate == null) return
        changeFilters { it.copy(filterFromDate = null, filterToDate = null) }
    }

    /**
     * Clear all filters (the panel footer, web issue #92): resets the five
     * panel filters only — the search box keeps its text — in a single
     * refetch. Nothing set: a no-op (the footer hides the button then).
     */
    fun clearPanelFilters() {
        if (!_uiState.value.filtersActive) return
        changeFilters(::panelFiltersCleared)
    }

    /**
     * Clear all (the filtered line, web issue #92): the five panel filters
     * AND the search — input and debounced needle together, so no late
     * debounce can resurrect the query (the pending job is cancelled
     * first) — one tap back to a fully clean list, in a single refetch.
     */
    fun clearFiltersAndSearch() {
        val state = _uiState.value
        if (!state.filtersActive && state.search.isEmpty() && state.searchNeedle.isEmpty()) return
        searchDebounceJob?.cancel()
        _uiState.update { panelFiltersCleared(it).copy(search = "", searchNeedle = "") }
        reload()
    }

    /**
     * The search input updates instantly; the request needle is trimmed and
     * debounced (~300ms, like the web app), then refetches the first page
     * like any filter. An unchanged needle never refetches.
     */
    fun onSearchChange(value: String) {
        _uiState.update { it.copy(search = value) }
        searchDebounceJob?.cancel()
        val needle = value.trim()
        if (searchDebounceMillis <= 0) {
            applyNeedle(needle)
        } else {
            searchDebounceJob = viewModelScope.launch {
                delay(searchDebounceMillis)
                applyNeedle(needle)
            }
        }
    }

    /**
     * Fetch the next page when more rows remain. The keyset cursor is never
     * parsed — it goes back verbatim. A failed load-more never auto-retries;
     * the screen offers an explicit retry.
     */
    fun loadMore() {
        val state = _uiState.value
        if (state.nextCursor == null || state.loadingMore || state.loadMoreError != null) return
        val cursor = state.nextCursor
        val filters = currentFilters()
        val gen = generation
        viewModelScope.launch {
            _uiState.update { it.copy(loadingMore = true) }
            try {
                val page = transactions.fetchPage(filters, cursor = cursor)
                if (gen != generation) return@launch
                _uiState.update { current ->
                    // The backend's keyset cursor never returns overlapping
                    // pages; the id-set is a defensive guard against stale
                    // responses.
                    val seen = current.transactions.mapTo(HashSet()) { it.id }
                    current.copy(
                        transactions = current.transactions + page.items.filter { seen.add(it.id) },
                        nextCursor = page.next_cursor,
                        loadingMore = false,
                    )
                }
            } catch (_: Exception) {
                if (gen != generation) return@launch
                _uiState.update {
                    it.copy(loadingMore = false, loadMoreError = "Could not load more transactions.")
                }
            }
        }
    }

    fun retryLoadMore() {
        _uiState.update { it.copy(loadMoreError = null) }
        loadMore()
    }

    fun retry() {
        _uiState.update { it.copy(loading = true, loadError = null) }
        reload()
    }

    // --- Transaction form (ticket #20) ---

    fun openCreate() {
        _uiState.update { state ->
            val active = activeWallets(state.wallets)
            val spendable = spendableWallets(state.wallets)
            state.copy(
                modal = ModalState(
                    type = TransactionType.EXPENSE,
                    date = Dates.toApiDay(Dates.todayInRome()),
                    walletId = spendable.firstOrNull()?.id,
                    sourceWalletId = active.firstOrNull()?.id,
                    destinationWalletId = active.getOrNull(1)?.id ?: active.firstOrNull()?.id,
                    // A location removed from an earlier create form opts the
                    // session out of the GPS prefill (web issue #25): the
                    // first-save prompt must not re-attach a position the
                    // user rejected.
                    locationOptedOut = sessionLocationOptedOut,
                ),
            )
        }
        refreshRecurringCosts()
        refreshRecurringIncomes()
        maybeGpsPrefill()
    }

    fun openEdit(transaction: TransactionDto) {
        _uiState.update { state ->
            state.copy(
                modal = ModalState(
                    editing = transaction,
                    type = when (transaction.type) {
                        TransactionType.TRANSFER -> TransactionType.TRANSFER
                        TransactionType.INCOME -> TransactionType.INCOME
                        else -> TransactionType.EXPENSE
                    },
                    amount = transaction.amount,
                    date = transaction.date,
                    walletId = if (transaction.type == TransactionType.TRANSFER) null else transaction.wallet_id,
                    sourceWalletId = transaction.source_wallet_id,
                    destinationWalletId = transaction.destination_wallet_id,
                    categoryId = transaction.category_id,
                    recurringCostId = transaction.recurring_cost_id,
                    recurringIncomeId = transaction.recurring_income_id,
                    description = transaction.description.orEmpty(),
                    // The stored Geographic Location seeds the form's
                    // location and its Place reference (ADR-0005) — an edit
                    // shows what the row carries until the user changes it.
                    location = latLngFromWire(transaction.latitude, transaction.longitude),
                    place = placeFromWire(transaction.place_name, transaction.place_id),
                ),
            )
        }
        refreshRecurringCosts()
        refreshRecurringIncomes()
    }

    fun closeModal() {
        _uiState.update { it.copy(modal = null) }
    }

    fun onTypeChange(value: TransactionType) {
        if (value == TransactionType.OPENING_BALANCE) return
        updateModal { modal ->
            if (modal.isEditing) {
                modal
            } else {
                val active = activeWallets(_uiState.value.wallets)
                val spendable = spendableWallets(_uiState.value.wallets)
                when (value) {
                    TransactionType.TRANSFER -> modal.copy(
                        type = value,
                        categoryId = null,
                        recurringCostId = null,
                        recurringIncomeId = null,
                        sourceWalletId = modal.sourceWalletId ?: active.firstOrNull()?.id,
                        destinationWalletId = modal.destinationWalletId
                            ?: (active.getOrNull(1)?.id ?: active.firstOrNull()?.id),
                        error = null,
                    )
                    TransactionType.INCOME -> {
                        // ADR-0017: an Income never rides a stale Contact
                        // Wallet selection to the API — reset to a spendable
                        // Wallet, like the initial seed.
                        val selected = modal.walletId
                            ?.let { id -> _uiState.value.wallets.find { it.id == id } }
                        modal.copy(
                            type = value,
                            walletId = if (selected?.type == WalletType.CONTACT) {
                                spendable.firstOrNull()?.id
                            } else {
                                modal.walletId ?: spendable.firstOrNull()?.id
                            },
                            categoryId = null,
                            recurringCostId = null,
                            error = null,
                        )
                    }
                    TransactionType.EXPENSE -> modal.copy(
                        type = value,
                        categoryId = null,
                        recurringIncomeId = null,
                        error = null,
                    )
                    TransactionType.OPENING_BALANCE -> modal
                }
            }
        }
    }

    fun onAmountChange(value: String) = updateModal { it.copy(amount = value, error = null) }

    fun onDateChange(value: String) = updateModal { it.copy(date = value, error = null) }

    fun onWalletChange(walletId: Int) = updateModal { it.copy(walletId = walletId, error = null) }

    fun onSourceWalletChange(walletId: Int) =
        updateModal { it.copy(sourceWalletId = walletId, error = null) }

    fun onDestinationWalletChange(walletId: Int) =
        updateModal { it.copy(destinationWalletId = walletId, error = null) }

    fun onCategoryChange(categoryId: Int?) = updateModal { it.copy(categoryId = categoryId, error = null) }

    /** The Expense form's Recurring Cost link pick (web issue #57): null =
     * no link (or, on an edit of a linked Expense, unlinking). */
    fun onRecurringCostChange(costId: Int?) =
        updateModal { it.copy(recurringCostId = costId, error = null) }

    /** The Income form's Recurring Income link pick (web issue #61), the
     * mirror: null = no link (or, on an edit of a linked Income, unlinking). */
    fun onRecurringIncomeChange(incomeId: Int?) =
        updateModal { it.copy(recurringIncomeId = incomeId, error = null) }

    fun onDescriptionChange(value: String) =
        updateModal { it.copy(description = value.take(DESCRIPTION_MAX_LENGTH), error = null) }

    // --- Location (ticket #29) ---

    /**
     * A pick from the map picker: the position always lands, and a pick
     * that carries a Place sets it — a coordinates-only pick (free-map tap
     * or a Google bare-map tap) clears any stored Place (ADR-0005), the
     * name must always match the coordinates. The pick closes the picker
     * and clears a GPS failure line.
     */
    fun onLocationPick(picked: LatLng, pickedPlace: Place?) =
        updateModal {
            it.copy(
                location = picked,
                place = pickedPlace,
                locationTouched = true,
                showingPicker = false,
                gpsError = null,
            )
        }

    /** The map picker's Cancel: the form's location is left untouched. */
    fun onLocationPickerCancel() = updateModal { it.copy(showingPicker = false) }

    /** The "Add location"/"Change location" press: opens the map picker
     * dialog behind the provider seam (ADR-0004). */
    fun onOpenLocationPicker() = updateModal { it.copy(showingPicker = true) }

    /**
     * Remove the location from the form: its Place goes with it (ADR-0005 —
     * a Place never survives without coordinates). On a new Transaction the
     * removal also opts the session out of the GPS prefill (web issue #25):
     * the first-save prompt must not silently re-attach a position the
     * user opted out of.
     */
    fun onRemoveLocation() {
        val editing = _uiState.value.modal?.isEditing == true
        if (!editing) sessionLocationOptedOut = true
        updateModal {
            it.copy(
                location = null,
                place = null,
                locationOptedOut = true,
                locationTouched = true,
                showingPicker = false,
                gpsError = null,
            )
        }
    }

    /**
     * The "Use my location" press: asks for the location permission when
     * it is not granted yet, then attaches the current position — a
     * coordinates-only pick that clears any stored Place (ADR-0005). While
     * the lookup runs the button reads "Locating…"; a denial, timeout, or
     * unavailable fix surfaces as the inline failure message, with the map
     * picker still one tap away.
     */
    fun onUseMyLocation() {
        val modal = _uiState.value.modal ?: return
        if (modal.locating || modal.busy) return
        updateModal { it.copy(locating = true, gpsError = null) }
        viewModelScope.launch {
            val granted = ensureLocationPermission()
            val position = if (granted) location.currentPosition() else null
            updateModal { current ->
                if (position == null) {
                    // Denied, timed out, or unavailable: say so instead of
                    // failing silently (the web form's exact copy).
                    current.copy(locating = false, gpsError = GPS_ERROR_TEXT)
                } else {
                    current.copy(
                        locating = false,
                        location = position,
                        // GPS is coordinates-only: any stored Place is stale
                        // the moment the coordinates move (ADR-0005).
                        place = null,
                        locationTouched = true,
                        showingPicker = false,
                        gpsError = null,
                    )
                }
            }
        }
    }

    /**
     * The screen's answer to the system location-permission prompt (the
     * modal's `requestingLocationPermission` flag raised it): completes the
     * pending request so the waiting GPS flow continues with the answer.
     */
    fun onLocationPermissionResult(granted: Boolean) {
        pendingLocationPermission?.let { request ->
            pendingLocationPermission = null
            request.complete(granted)
        }
        updateModal { it.copy(requestingLocationPermission = false) }
    }

    /** Ask the platform for the location permission when it is not granted
     * yet: raises the modal's prompt flag for the screen and suspends until
     * the answer arrives. A second caller while a prompt is already up
     * waits on the same request — one system dialog at a time. */
    private suspend fun ensureLocationPermission(): Boolean {
        if (location.permissionGranted()) return true
        val pending = pendingLocationPermission
        if (pending != null) return pending.await()
        val request = CompletableDeferred<Boolean>()
        pendingLocationPermission = request
        updateModal { it.copy(requestingLocationPermission = true) }
        try {
            return request.await()
        } finally {
            if (pendingLocationPermission === request) pendingLocationPermission = null
        }
    }

    /**
     * GPS prefill (web parity): when a new Transaction form opens and the
     * device already holds the location permission, prefill the location
     * from the current position so recording takes one tap. No prompt is
     * raised here — the browser never prompts either; it only prompts on
     * the first save. A user-chosen or user-removed location is never
     * overwritten by a pending prefill.
     */
    private fun maybeGpsPrefill() {
        if (sessionLocationOptedOut) return
        viewModelScope.launch {
            if (!location.permissionGranted()) return@launch
            val position = location.currentPosition() ?: return@launch
            _uiState.update { state ->
                val modal = state.modal
                if (modal == null || modal.isEditing || modal.locationTouched ||
                    modal.locationOptedOut || modal.location != null
                ) {
                    state
                } else {
                    state.copy(modal = modal.copy(location = position))
                }
            }
        }
    }

    // --- Inline entity creation (ADR-0013, ticket #21) ---

    /**
     * A "New wallet…" pick from a Wallet select (the form must be open and
     * creating — the Wallet fields freeze while editing): stack the create
     * form on the Transaction form, with the eligibility lock the originating
     * field applies. The draft stays untouched until the create form's save
     * reports the new Wallet back.
     */
    fun onWalletAdd(target: WalletFieldTarget) {
        _uiState.update { state ->
            val modal = state.modal
            if (modal == null || modal.isEditing || modal.busy) return@update state
            if (state.walletCreate != null || state.categoryCreate != null) return@update state
            state.copy(
                walletCreate = WalletCreateState(
                    target = target,
                    allowedTypes = walletCreateAllowedTypes(modal.type, target),
                    modal = WalletModalState(),
                ),
            )
        }
    }

    fun onWalletCreateNameChange(value: String) =
        updateWalletCreate { it.copy(name = value, error = null) }

    fun onWalletCreateTypeChange(value: WalletType) = updateWalletCreate {
        if (value == WalletType.CONTACT) {
            // A Contact wallet starts at €0: drop any drafted amount.
            it.copy(type = value, openingBalance = "", error = null)
        } else {
            it.copy(type = value, error = null)
        }
    }

    fun onWalletCreateOpeningBalanceChange(value: String) =
        updateWalletCreate { it.copy(openingBalance = value, error = null) }

    /** Cancel only the inline form: the Transaction draft stays as it was. */
    fun cancelWalletCreate() {
        _uiState.update { it.copy(walletCreate = null) }
    }

    /**
     * Confirm the inline "New wallet…" form: the Wallet is created for real
     * through the same endpoint the Wallets screen uses (ADR-0014), appended
     * to the form's list, and auto-selected into the exact field whose
     * sentinel was picked — the Transaction draft's other fields untouched,
     * so the form can be submitted immediately.
     */
    fun submitWalletCreate() {
        val create = _uiState.value.walletCreate ?: return
        val modal = create.modal
        if (!modal.canSubmit) return
        val openingBalance = if (modal.type == WalletType.CONTACT) {
            "0.00"
        } else {
            normalizeOpeningBalance(modal.openingBalance)
        }
        if (openingBalance == null) {
            updateWalletCreate { it.copy(error = "Enter an amount of €0 or more.") }
            return
        }
        val target = create.target
        viewModelScope.launch {
            updateWalletCreate { it.copy(submitting = true, error = null) }
            try {
                val created = wallets.createWallet(modal.name.trim(), modal.type, openingBalance)
                _uiState.update { state ->
                    val transactionModal = state.modal?.let { form ->
                        when (target) {
                            WalletFieldTarget.WALLET -> form.copy(walletId = created.id)
                            WalletFieldTarget.SOURCE -> form.copy(sourceWalletId = created.id)
                            WalletFieldTarget.DESTINATION -> form.copy(destinationWalletId = created.id)
                        }
                    }
                    state.copy(
                        wallets = state.wallets + created,
                        modal = transactionModal ?: state.modal,
                        walletCreate = null,
                    )
                }
            } catch (error: ApiException) {
                updateWalletCreate {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A wallet with this name already exists.",
                            "Could not create the wallet.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateWalletCreate { it.copy(submitting = false, error = "Could not create the wallet.") }
            }
        }
    }

    /**
     * A "New category…" pick from the Category select (an Expense or Income
     * form — a Transfer never carries one): stack the create form on the
     * Transaction form, its type locked to the form's current type.
     */
    fun onCategoryAdd() {
        _uiState.update { state ->
            val modal = state.modal
            if (modal == null || modal.isTransfer || modal.busy) return@update state
            if (state.categoryCreate != null || state.walletCreate != null) return@update state
            val locked = when (modal.type) {
                TransactionType.INCOME -> CategoryType.INCOME
                else -> CategoryType.EXPENSE
            }
            state.copy(
                categoryCreate = CategoryCreateState(
                    lockedType = locked,
                    modal = CategoryModalState(type = locked),
                ),
            )
        }
    }

    fun onCategoryCreateNameChange(value: String) =
        updateCategoryCreate { it.copy(name = value, error = null) }

    fun onCategoryCreateIconChange(value: String) =
        updateCategoryCreate { it.copy(icon = value, error = null) }

    fun onCategoryCreateColorChange(value: String) =
        updateCategoryCreate { it.copy(color = value, error = null) }

    /** Cancel only the inline form: the Transaction draft stays as it was. */
    fun cancelCategoryCreate() {
        _uiState.update { it.copy(categoryCreate = null) }
    }

    /**
     * Confirm the inline "New category…" form: the Category is created for
     * real through the same endpoint the Categories screen uses (ADR-0014),
     * appended to the form's list, and auto-selected into the Category
     * field — the Transaction draft's other fields untouched.
     */
    fun submitCategoryCreate() {
        val create = _uiState.value.categoryCreate ?: return
        val modal = create.modal
        if (!modal.canSubmit) return
        val lockedType = create.lockedType
        viewModelScope.launch {
            updateCategoryCreate { it.copy(submitting = true, error = null) }
            try {
                val created = categories.createCategory(
                    modal.name.trim(),
                    lockedType,
                    modal.icon,
                    modal.color,
                )
                _uiState.update { state ->
                    state.copy(
                        categories = state.categories + created,
                        modal = state.modal?.copy(categoryId = created.id),
                        categoryCreate = null,
                    )
                }
            } catch (error: ApiException) {
                updateCategoryCreate {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A category with this name already exists.",
                            "Could not create the category.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateCategoryCreate { it.copy(submitting = false, error = "Could not create the category.") }
            }
        }
    }

    /**
     * Export the ledger exactly as the filters and the search show it
     * (US 7.3, ticket #28): the whole matching set — not just the visible
     * page — fetched through the gateway and handed to the screen as a
     * pending `exportFile`. The screen's two Export to Excel buttons — the
     * filtered chips line and the filter panel's footer (web issue #92,
     * ticket #35) — are the web's only entry points; the header no longer
     * carries one. One press, one request: a press while one is
     * already in flight is ignored, and a press supersedes a file the
     * screen has not yet shared. A failure surfaces as the export error
     * line with the web's copy; the ledger itself is untouched (a GET
     * never bumps the data version, ADR-0002).
     */
    fun export() {
        if (_uiState.value.exporting) return
        val filters = currentFilters()
        viewModelScope.launch {
            // The in-flight guard, checked again under the launch: on a
            // confined Main dispatcher two taps in the same frame could
            // both pass the check above before the state lands.
            if (_uiState.value.exporting) return@launch
            _uiState.update { it.copy(exporting = true, exportError = null, exportFile = null) }
            try {
                val file = transactions.export(filters)
                _uiState.update { it.copy(exporting = false, exportFile = file) }
            } catch (error: ApiException) {
                _uiState.update {
                    it.copy(
                        exporting = false,
                        exportError = apiErrorMessage(
                            error.status,
                            "Could not export transactions.",
                            "Could not export transactions.",
                        ),
                    )
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(exporting = false, exportError = "Could not export transactions.") }
            }
        }
    }

    /** The screen shared (or failed to share) the pending export: clear it
     * so the same file can never leave the app twice. */
    fun onExportHandled() {
        _uiState.update { it.copy(exportFile = null) }
    }

    /** A client-side failure while saving or sharing the fetched file (the
     * export request itself succeeded): surface it as the export error
     * line, the same place a failed request reports. */
    fun onExportError(message: String) {
        _uiState.update { it.copy(exportError = message) }
    }

    fun submit() {
        val modal = _uiState.value.modal ?: return
        if (!modal.canSubmit) return
        if (modal.isEditing) update(modal) else create()
    }

    fun onDeleteTap() {
        val modal = _uiState.value.modal ?: return
        val transaction = modal.editing ?: return
        if (modal.busy) return
        if (!modal.confirmingDelete) {
            updateModal { it.copy(confirmingDelete = true, error = null) }
            return
        }
        viewModelScope.launch {
            updateModal { it.copy(deleting = true, error = null) }
            try {
                val result = transactions.deleteTransaction(transaction.id)
                _uiState.update { state ->
                    state.copy(
                        modal = null,
                        savedWarning = if (result.warning) {
                            "Deleted — this made a Cash wallet negative."
                        } else {
                            null
                        },
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        confirmingDelete = false,
                        deleting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A wallet or category with this name already exists.",
                            "Could not delete the transaction.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal {
                    it.copy(
                        confirmingDelete = false,
                        deleting = false,
                        error = "Could not delete the transaction.",
                    )
                }
            }
        }
    }

    private fun create() {
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            // First-save permission (web parity): when creating without a
            // location, ask for the location permission — the platform
            // prompts once — and attach the position when granted. A
            // location the user removed (locationOptedOut) is never
            // overridden, and a denial saves without a location.
            var modal = _uiState.value.modal ?: return@launch
            if (modal.location == null && !modal.locationOptedOut) {
                if (ensureLocationPermission()) {
                    val position = location.currentPosition()
                    if (position != null) {
                        updateModal { it.copy(location = position) }
                    }
                }
                modal = _uiState.value.modal ?: return@launch
            }
            try {
                val saved = transactions.createTransaction(draftOf(modal))
                _uiState.update { state ->
                    state.copy(
                        modal = null,
                        savedWarning = if (saved.warning) {
                            "Saved — this made a Cash wallet negative."
                        } else {
                            null
                        },
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A wallet or category with this name already exists.",
                            "Could not create the transaction.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not create the transaction.") }
            }
        }
    }

    private fun update(modal: ModalState) {
        val transaction = modal.editing ?: return
        viewModelScope.launch {
            updateModal { it.copy(submitting = true, error = null) }
            try {
                val saved = transactions.updateTransaction(transaction.id, draftOf(modal))
                _uiState.update { state ->
                    state.copy(
                        modal = null,
                        savedWarning = if (saved.warning) {
                            "Saved — this made a Cash wallet negative."
                        } else {
                            null
                        },
                    )
                }
            } catch (error: ApiException) {
                updateModal {
                    it.copy(
                        submitting = false,
                        error = apiErrorMessage(
                            error.status,
                            "A wallet or category with this name already exists.",
                            "Could not save the transaction.",
                        ),
                    )
                }
            } catch (_: Exception) {
                updateModal { it.copy(submitting = false, error = "Could not save the transaction.") }
            }
        }
    }

    private fun draftOf(modal: ModalState): TransactionDraft = TransactionDraft(
        type = modal.type,
        amount = modal.amount.trim(),
        date = modal.date,
        walletId = if (modal.isTransfer) null else modal.walletId,
        sourceWalletId = if (modal.isTransfer) modal.sourceWalletId else null,
        destinationWalletId = if (modal.isTransfer) modal.destinationWalletId else null,
        categoryId = if (modal.isTransfer) null else modal.categoryId,
        // A picked link rides only on its own type — an Expense on a
        // Recurring Cost, an Income on a Recurring Income (the type reset
        // drops a pick, and this guard keeps a stray pick off the wire); a
        // link change is a PATCH key the form sends only when the pick
        // actually changed (the stored pin must survive a mere
        // amount/date edit).
        recurringCostId = if (modal.type == TransactionType.EXPENSE) modal.recurringCostId else null,
        recurringCostTouched = modal.recurringCostId != modal.editing?.recurring_cost_id,
        recurringIncomeId = if (modal.type == TransactionType.INCOME) modal.recurringIncomeId else null,
        recurringIncomeTouched = modal.recurringIncomeId != modal.editing?.recurring_income_id,
        description = modal.description.trim().ifEmpty { null },
        // The form's location rides as-is; the Place only ever travels with
        // coordinates — a locationless form clears the place keys on the
        // wire (ADR-0005: a Place without coordinates is outside the
        // model).
        location = modal.location,
        place = modal.place.takeIf { modal.location != null },
    )

    /** The five panel filters reset to "all" (web issue #92's taxonomy) —
     * wallet, category, from-date, to-date, recurring. The search is
     * separate and untouched here: the panel footer's Clear all filters
     * clears exactly these, and the filtered line's Clear all clears the
     * search on top. */
    private fun panelFiltersCleared(state: UiState): UiState = state.copy(
        filterWalletId = null,
        filterFromDate = null,
        filterToDate = null,
        filterCategoryId = null,
        filterRecurring = null,
    )

    private fun changeFilters(transform: (UiState) -> UiState) {
        _uiState.update(transform)
        reload()
    }

    private fun updateModal(transform: (ModalState) -> ModalState) {
        _uiState.update { state ->
            state.modal?.let { state.copy(modal = transform(it)) } ?: state
        }
    }

    private fun updateWalletCreate(transform: (WalletModalState) -> WalletModalState) {
        _uiState.update { state ->
            state.walletCreate?.let { state.copy(walletCreate = it.copy(modal = transform(it.modal))) } ?: state
        }
    }

    private fun updateCategoryCreate(transform: (CategoryModalState) -> CategoryModalState) {
        _uiState.update { state ->
            state.categoryCreate?.let {
                state.copy(categoryCreate = it.copy(modal = transform(it.modal)))
            } ?: state
        }
    }

    private fun applyNeedle(needle: String) {
        if (needle == _uiState.value.searchNeedle) return
        _uiState.update { it.copy(searchNeedle = needle) }
        reload()
    }

    private fun currentFilters(): TransactionFilters {
        val state = _uiState.value
        val recurring = state.filterRecurring
        return TransactionFilters(
            walletId = state.filterWalletId,
            categoryId = state.filterCategoryId,
            fromDate = state.filterFromDate,
            toDate = state.filterToDate,
            // The single pick travels under its kind's own key — a cost and
            // an income that share an id can never be confused, and at most
            // one key is ever on the wire (web issue #86).
            recurringCostId = if (recurring?.kind == RecurringFilterKind.COST) recurring.id else null,
            recurringIncomeId = if (recurring?.kind == RecurringFilterKind.INCOME) recurring.id else null,
            q = state.searchNeedle.ifEmpty { null },
        )
    }

    /**
     * Fetch the first page with the current filters and search applied,
     * together with the wallets and categories the rows and the filter bar
     * render from (one request set, like the web app's Promise.all). A
     * failed background refetch keeps the held data on screen (ADR-0002); a
     * failure with nothing to show surfaces the error.
     */
    private fun reload() {
        generation++
        val filters = currentFilters()
        val gen = generation
        _uiState.update { it.copy(loadingMore = false, loadMoreError = null) }
        viewModelScope.launch {
            try {
                val (loadedWallets, loadedCategories, page) = coroutineScope {
                    val walletsDeferred = async { wallets.fetchWallets() }
                    val categoriesDeferred = async { categories.fetchCategories() }
                    val pageDeferred = async { transactions.fetchPage(filters) }
                    Triple(walletsDeferred.await(), categoriesDeferred.await(), pageDeferred.await())
                }
                if (gen != generation) return@launch
                _uiState.update { state ->
                    state.copy(
                        wallets = loadedWallets,
                        categories = loadedCategories,
                        transactions = page.items,
                        nextCursor = page.next_cursor,
                        loadingMore = false,
                        loadMoreError = null,
                        loadError = null,
                        loading = false,
                        // The unfiltered, unsearched fetch is the truth about
                        // the ledger: only when it returns nothing is the
                        // ledger truly empty (and the search bar hidden).
                        ledgerEmpty = if (!filters.active && filters.q == null) {
                            page.items.isEmpty()
                        } else {
                            state.ledgerEmpty
                        },
                    )
                }
            } catch (_: Exception) {
                if (gen != generation) return@launch
                _uiState.update { state ->
                    // A failure before anything loaded surfaces the error; a
                    // failed background refetch keeps the held data (the
                    // `loading` flag tells the two apart).
                    if (state.loading) {
                        state.copy(loading = false, loadError = "Could not load your data.")
                    } else {
                        state.copy(loading = false)
                    }
                }
            }
            // The Recurring definitions ride on every reload (ADR-0002):
            // the filter bar's select and the form pickers list them, so a
            // definition created, edited, skipped, or deleted anywhere
            // reaches both without reopening anything — never the other way
            // around, an optional fetch failure is not a ledger error.
            refreshRecurringCosts()
            refreshRecurringIncomes()
        }
    }

    /**
     * The Recurring Cost definitions the filter bar's Recurring select and
     * the Expense form's link picker list (web issues #86/#57), fetched on
     * every reload — so a definition created, edited, skipped, or deleted
     * anywhere reaches both without reopening anything. The list is
     * optional: a failed fetch silently keeps the held definitions (an
     * empty or stale picker never blocks the ledger), unlike the
     * wallets/categories the screen itself renders from.
     */
    private fun refreshRecurringCosts() {
        viewModelScope.launch {
            try {
                val loaded = recurringCosts.fetchRecurringCosts()
                _uiState.update { it.copy(recurringCosts = loaded) }
            } catch (_: Exception) {
                // The picker keeps its held definitions.
            }
        }
    }

    /** The Recurring Income definitions the filter bar's Recurring select
     * and the Income form's link picker list (web issues #86/#61), the
     * mirror of the cost side: fetched on every reload; a failed fetch
     * silently keeps the held definitions. */
    private fun refreshRecurringIncomes() {
        viewModelScope.launch {
            try {
                val loaded = recurringIncomes.fetchRecurringIncomes()
                _uiState.update { it.copy(recurringIncomes = loaded) }
            } catch (_: Exception) {
                // The picker keeps its held definitions.
            }
        }
    }

    companion object {
        /** The web app's ~300ms debounce for the search needle. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L

        /** The Description field's cap (CONTEXT.md: up to 500 characters). */
        const val DESCRIPTION_MAX_LENGTH = 500

        /** The inline GPS failure line (the web form's exact copy): a
         * denied, timed-out, or unavailable fix — the map picker stays one
         * tap away. */
        const val GPS_ERROR_TEXT =
            "Couldn't get your location — check permissions or pick it on the map."
    }
}
