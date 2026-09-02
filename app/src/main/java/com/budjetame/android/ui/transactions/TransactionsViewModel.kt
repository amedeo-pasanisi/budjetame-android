package com.budjetame.android.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.DataVersion
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.transaction.TransactionFilters
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
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
 * The Transactions ledger's state machine (ticket #19), ported from the web
 * app's TransactionsScreen read path: a newest-first list with cursor paging
 * (50 per page) and infinite scroll, the collapsible filter bar (wallet —
 * frozen ones included — date range, category), and the debounced
 * description search — any filter or search change refetches the first page
 * with everything applied, and further pages keep the filters and hand the
 * opaque cursor back verbatim. Data is refetched in the background when the
 * global data version bumps (ADR-0002).
 */
class TransactionsViewModel(
    private val transactions: TransactionGateway,
    private val wallets: WalletGateway,
    private val categories: CategoryGateway,
    private val searchDebounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val loadError: String? = null,
        val wallets: List<WalletDto> = emptyList(),
        val categories: List<CategoryDto> = emptyList(),
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
        val search: String = "",
        val searchNeedle: String = "",
    ) {
        /** True when any Filters-bar field is set — the search needle is
         * separate, it rides along with the bar's fields (ADR-0009). */
        val filtersActive: Boolean
            get() = filterWalletId != null || filterFromDate != null ||
                filterToDate != null || filterCategoryId != null

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
     * Monotonic reset counter: every reload (filter/search change, version
     * bump) starts a new generation, and a page fetch from an earlier
     * generation is discarded on arrival — so a further page still in
     * flight when a reset happens never appends its pre-reset rows.
     */
    private var generation = 0

    private var searchDebounceJob: Job? = null

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

    private fun changeFilters(transform: (UiState) -> UiState) {
        _uiState.update(transform)
        reload()
    }

    private fun applyNeedle(needle: String) {
        if (needle == _uiState.value.searchNeedle) return
        _uiState.update { it.copy(searchNeedle = needle) }
        reload()
    }

    private fun currentFilters(): TransactionFilters {
        val state = _uiState.value
        return TransactionFilters(
            walletId = state.filterWalletId,
            categoryId = state.filterCategoryId,
            fromDate = state.filterFromDate,
            toDate = state.filterToDate,
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
        }
    }

    companion object {
        /** The web app's ~300ms debounce for the search needle. */
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
