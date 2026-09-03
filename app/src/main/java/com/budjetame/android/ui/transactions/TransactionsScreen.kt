package com.budjetame.android.ui.transactions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.categories.CategoryModal
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.ui.imports.ImportScreen
import com.budjetame.android.ui.imports.ImportViewModel
import com.budjetame.android.ui.wallets.WalletModal
import com.budjetame.android.util.Dates
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AMBER_50 = Color(0xFFFFFBEB)
private val AMBER_200 = Color(0xFFFDE68A)
private val AMBER_700 = Color(0xFFB45309)

/**
 * The Transactions tab (ticket #19): the ledger read path — a newest-first
 * list with cursor paging (50 per page) and infinite scroll, the collapsible
 * filter bar (wallet, frozen ones included and marked, date range,
 * category, recurring definition (web issue #86)), and the debounced
 * description search. Rows on Frozen Wallets
 * render read-only (dimmed, no entry points — the edit/delete entry points
 * themselves land with ticket #20's forms and honor `isEditable`). The
 * header's Import button opens the bulk Import flow (ticket #26), which
 * replaces the tab's content while its Draft is open — the web screen's
 * shape; the draft lives in its own ViewModel on this tab's back-stack
 * entry, so it survives tab switches (ADR-0002). The chrome mirrors the web
 * app's v1.2.0 screen (web issue #92, ticket #35): the header row carries
 * only the title, Import, and New transaction — Export left it entirely;
 * the search field row is the toolbar with the Filters toggle at its right;
 * a filtered chips line (visible while a panel filter is set) and the
 * filter panel's footer are the web's two Export to Excel entry points
 * (ticket #28's flow unchanged underneath: the whole filtered ledger +
 * search as the import template's .xlsx, handed to the system share sheet).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionsScreen(
    transactions: TransactionGateway,
    imports: ImportGateway,
    wallets: WalletGateway,
    categories: CategoryGateway,
    recurringCosts: RecurringCostGateway,
    recurringIncomes: RecurringIncomeGateway,
    /** The device GPS (ticket #29): backs the Transaction form's "Use my
     * location" pick, its prefill, and the first-save attach. */
    location: DeviceLocation,
) {
    val viewModel: TransactionsViewModel = viewModel {
        TransactionsViewModel(
            transactions,
            wallets,
            categories,
            recurringCosts,
            recurringIncomes,
            location = location,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // The location-permission prompt (ticket #29): the ViewModel raises the
    // modal's requestingLocationPermission flag when a GPS flow needs the
    // platform permission ("Use my location" or the first save of a new
    // Transaction); this launcher shows the one-time system dialog and
    // reports the answer back so the flow continues.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocationPermissionResult(granted) }
    val askingLocationPermission = state.modal?.requestingLocationPermission == true
    LaunchedEffect(askingLocationPermission) {
        if (askingLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // The Import Draft (web issue #43, ticket #26): its own ViewModel on
    // the Transactions tab's back-stack entry — the draft survives tab
    // switches with the entry (ADR-0002's keep-alive), discarded only by
    // Cancel, picking another file, or a successful import. The entity
    // gateways back the row editor's inline Wallet/Category creation
    // (ADR-0014, ticket #27).
    val importViewModel: ImportViewModel = viewModel {
        ImportViewModel(imports, wallets, categories)
    }
    val importUi by importViewModel.uiState.collectAsStateWithLifecycle()
    val importDraft = importUi.draft

    if (importDraft != null) {
        // While a Draft is open the Import flow replaces the tab's content
        // (the web screen's shape): the header's New transaction and Import
        // buttons give way to the flow's own Cancel/Back — Export hides
        // with them, like the web screen. The ledger below keeps its state
        // and refetches in the background (ADR-0002), so a successful
        // import's write bump refreshes it before Back returns.
        ImportScreen(
            draft = importDraft,
            wallets = state.wallets,
            categories = state.categories,
            viewModel = importViewModel,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // Export (US 7.3, ticket #28): when the ViewModel hands over the
    // fetched workbook, write it to the app's cache and open the system
    // share sheet on it — the native equivalent of the web's download,
    // where SAF-backed targets (Files, Drive, mail) save or share the
    // .xlsx. The file is shared once; the ViewModel clears it either way.
    val context = LocalContext.current
    val exportFile = state.exportFile
    LaunchedEffect(exportFile) {
        val file = exportFile ?: return@LaunchedEffect
        val uri = withContext(Dispatchers.IO) { cacheExportFile(context, file) }
        if (uri == null) {
            viewModel.onExportError("Could not save the export file.")
        } else {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = EXPORT_MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(Intent.createChooser(send, null))
            } catch (_: Exception) {
                // No app on the device can take the file (a bare test
                // harness): the error line reports it and the export is
                // consumed, so a later press fetches afresh.
                viewModel.onExportError("Could not share the export file.")
            }
        }
        viewModel.onExportHandled()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // The header row (web issue #92, ticket #35): the title takes its
        // natural width — never squeezed behind the actions, which is what
        // used to make it wrap mid-word — and the actions (Import as the
        // web's plain text link, then the filled New transaction) follow
        // it in the web's order. The row is a FlowRow: an action that does
        // not fit wraps to a second line as a whole item, so a label can
        // never break mid-word at 360dp+/1.3x. Export is gone from the
        // header — the web's two entry points sit on the filtered chips
        // line and in the filter panel's footer, below.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Transactions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = importViewModel::open) {
                Text("Import")
            }
            Button(
                onClick = viewModel::openCreate,
                enabled = !state.loading && state.loadError == null,
            ) {
                Text("New transaction")
            }
        }

        state.exportError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        state.savedWarning?.let { warning ->
            WarningBanner(
                text = warning,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val loadError = state.loadError
        when {
            state.loading -> MessageBody(
                text = "Loading…",
                modifier = Modifier.weight(1f),
            )
            loadError != null -> LoadErrorBody(
                message = loadError,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f),
            )
            else -> Ledger(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }

    state.modal?.let { modal ->
        TransactionModal(
            modal = modal,
            wallets = state.wallets,
            categories = state.categories,
            recurringCosts = state.recurringCosts,
            recurringIncomes = state.recurringIncomes,
            onTypeChange = viewModel::onTypeChange,
            onAmountChange = viewModel::onAmountChange,
            onDateChange = viewModel::onDateChange,
            onWalletChange = viewModel::onWalletChange,
            onSourceWalletChange = viewModel::onSourceWalletChange,
            onDestinationWalletChange = viewModel::onDestinationWalletChange,
            onCategoryChange = viewModel::onCategoryChange,
            onRecurringCostChange = viewModel::onRecurringCostChange,
            onRecurringIncomeChange = viewModel::onRecurringIncomeChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onAddWallet = viewModel::onWalletAdd,
            onAddCategory = viewModel::onCategoryAdd,
            onSubmit = viewModel::submit,
            onDelete = viewModel::onDeleteTap,
            onClose = viewModel::closeModal,
            onLocationPick = viewModel::onLocationPick,
            onRemoveLocation = viewModel::onRemoveLocation,
            onUseMyLocation = viewModel::onUseMyLocation,
            onOpenLocationPicker = viewModel::onOpenLocationPicker,
            onCloseLocationPicker = viewModel::onLocationPickerCancel,
            onOpenMapLink = { url -> openMapLink(context, url) },
        )
    }

    // Inline entity creation (ADR-0013, ticket #21): the entity's create
    // modal, stacked on the Transaction form. Composed after it, its dialog
    // window renders on top; a Cancel or back press closes only this one —
    // the Transaction draft survives below.
    state.walletCreate?.let { create ->
        WalletModal(
            modal = create.modal,
            allowedTypes = create.allowedTypes,
            onNameChange = viewModel::onWalletCreateNameChange,
            onTypeChange = viewModel::onWalletCreateTypeChange,
            onOpeningBalanceChange = viewModel::onWalletCreateOpeningBalanceChange,
            onSubmit = viewModel::submitWalletCreate,
            onFreeze = {}, // Create-only: the freeze section never renders.
            onClose = viewModel::cancelWalletCreate,
        )
    }

    state.categoryCreate?.let { create ->
        CategoryModal(
            modal = create.modal,
            lockedType = create.lockedType,
            onNameChange = viewModel::onCategoryCreateNameChange,
            onTypeChange = {}, // Locked: the Type selector never renders.
            onIconChange = viewModel::onCategoryCreateIconChange,
            onColorChange = viewModel::onCategoryCreateColorChange,
            onSubmit = viewModel::submitCategoryCreate,
            onMerge = {}, // Create-only: the merge/delete sections never render.
            onCancelMerge = {},
            onDelete = {},
            onClose = viewModel::cancelCategoryCreate,
        )
    }
}

@Composable
private fun Ledger(
    state: TransactionsViewModel.UiState,
    viewModel: TransactionsViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // The toolbar row (web issue #92, ticket #35): the search field
        // takes the width and the Filters ▸/▾ toggle sits at its right —
        // the old "All transactions" label row is gone. A truly empty
        // ledger hides the whole row (web behavior; the search alone was
        // hidden before): there is nothing to search or filter.
        if (!state.ledgerEmpty) {
            item(key = "toolbar") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    SearchField(
                        value = state.search,
                        onValueChange = viewModel::onSearchChange,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = viewModel::toggleFilters) {
                        Text(if (state.filtersOpen) "Filters ▾" else "Filters ▸")
                    }
                }
            }
        }

        // The filtered chips line (web issue #92, ticket #35): visible
        // only while at least one of the five panel filters is set — the
        // search never appears here.
        if (state.filtersActive) {
            item(key = "filtered-chips") {
                FilterChipsLine(state = state, viewModel = viewModel)
            }
        }

        if (state.filtersOpen) {
            item(key = "filters") {
                FilterBar(state = state, viewModel = viewModel)
            }
        }

        state.selectedWallet?.let { selected ->
            if (selected.frozen) {
                item(key = "frozen-banner") {
                    FrozenBanner(
                        text = "This wallet is frozen — its history is viewable but read-only.",
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (state.transactions.isEmpty()) {
            item(key = "empty") {
                MessageBody(text = state.emptyMessage)
            }
        } else {
            items(state.transactions, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    state = state,
                    onOpenEdit = { viewModel.openEdit(transaction) },
                )
            }
            if (state.nextCursor != null || state.loadMoreError != null) {
                item(key = "load-more") {
                    LoadMoreSentinel(
                        loading = state.loadingMore,
                        error = state.loadMoreError,
                        onAppear = viewModel::loadMore,
                        onRetry = viewModel::retryLoadMore,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text("Search transactions…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        modifier = modifier
            .fillMaxWidth()
            .testTag("tx-search"),
    )
}

/**
 * The web's Export entry-point label (ticket #35): a plain text button
 * reading "Export to Excel" — the web's copy everywhere, the old
 * "Export" is gone — on the filtered chips line and in the filter
 * panel's footer. While a request is in flight every export button
 * disables and reads "Exporting…" (ticket #28's one press = one
 * request).
 */
@Composable
private fun ExportToExcelButton(exporting: Boolean, onExport: () -> Unit) {
    TextButton(onClick = onExport, enabled = !exporting) {
        Text(if (exporting) "Exporting…" else "Export to Excel")
    }
}

/**
 * The filtered chips line (web issue #92, ticket #35): one chip per
 * active panel filter — wallet, category, the date range (From and To
 * merge into one chip when both are set), the recurring definition — in
 * that order, each with its own ✕ that removes just that filter; a set
 * filter whose entity the loaded lists do not know yet shows no chip (the
 * panel select remains the way back to it). Clear all (the five filters
 * AND the search — web semantics) and Export to Excel sit on the right.
 * The chips wrap to further lines when the width needs it; the actions
 * keep their natural width.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsLine(
    state: TransactionsViewModel.UiState,
    viewModel: TransactionsViewModel,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            activeFilterChips(state).forEach { chip ->
                FilterChip(label = chip.label) { removeFilterChip(viewModel, chip.key) }
            }
        }
        TextButton(onClick = viewModel::clearFiltersAndSearch) {
            Text("Clear all")
        }
        ExportToExcelButton(exporting = state.exporting, onExport = viewModel::export)
    }
}

/** A chip's ✕ routes by key to the one filter it stands for (web issue
 * #92): the wallet/category/recurring chips clear their single filter
 * like the panel's "All" pick; the merged date chip clears both date
 * bounds together. */
private fun removeFilterChip(viewModel: TransactionsViewModel, key: FilterChipKey) {
    when (key) {
        FilterChipKey.WALLET -> viewModel.onFilterWalletChange(null)
        FilterChipKey.CATEGORY -> viewModel.onFilterCategoryChange(null)
        FilterChipKey.DATES -> viewModel.clearFilterDates()
        FilterChipKey.RECURRING -> viewModel.onFilterRecurringChange(null)
    }
}

/** One filtered-line chip: a pill with the filter's label and its ✕,
 * whose content description mirrors the web's aria-label
 * ("Remove <label> filter"). */
@Composable
private fun FilterChip(label: String, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            )
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClick = onRemove)
                    .semantics { contentDescription = "Remove $label filter" }
                    .padding(start = 2.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            )
        }
    }
}

/** The collapsible filter bar (closed by default): wallet, date range,
 * recurring definition, category — every change refetches the first page
 * with it applied. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    state: TransactionsViewModel.UiState,
    viewModel: TransactionsViewModel,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WalletFilterField(
                selected = state.selectedWallet,
                wallets = state.wallets,
                onSelect = viewModel::onFilterWalletChange,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DateFilterField(
                    label = "From",
                    value = state.filterFromDate,
                    onSelect = viewModel::onFilterFromDateChange,
                    modifier = Modifier.weight(1f),
                )
                DateFilterField(
                    label = "To",
                    value = state.filterToDate,
                    onSelect = viewModel::onFilterToDateChange,
                    modifier = Modifier.weight(1f),
                )
            }
            RecurringFilterField(
                selected = state.filterRecurring,
                costs = state.recurringCosts,
                incomes = state.recurringIncomes,
                onSelect = viewModel::onFilterRecurringChange,
            )
            CategoryFilterField(
                selected = state.categories.find { it.id == state.filterCategoryId },
                categories = state.categories,
                onSelect = viewModel::onFilterCategoryChange,
            )

            // Panel footer (web issue #92, ticket #35): Clear all filters —
            // visible only while at least one of the five panel filters is
            // set, and it clears exactly those, the search untouched — with
            // Export to Excel always present while the panel is open
            // (nothing set: the full-ledger export path).
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.filtersActive) {
                    TextButton(onClick = viewModel::clearPanelFilters) {
                        Text("Clear all filters")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                ExportToExcelButton(exporting = state.exporting, onExport = viewModel::export)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletFilterField(
    selected: WalletDto?,
    wallets: List<WalletDto>,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let(::walletFilterLabel) ?: "All wallets",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Wallet") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All wallets") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            wallets.forEach { wallet ->
                DropdownMenuItem(
                    text = { Text(walletFilterLabel(wallet)) },
                    onClick = {
                        onSelect(wallet.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * The Recurring definition filter (web issue #86, ticket #25): one select
 * listing the Recurring Costs and the Recurring Incomes, each under its
 * kind caption — the web select's optgroups; names may collide across
 * kinds — plus "All transactions". Picking a definition narrows the
 * ledger to the Transactions linked to exactly it; every change refetches
 * the first page like any filter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecurringFilterField(
    selected: RecurringFilter?,
    costs: List<RecurringCostDto>,
    incomes: List<RecurringIncomeDto>,
    onSelect: (RecurringFilter?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = recurringFilterLabel(selected, costs, incomes),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Recurring") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All transactions") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            if (costs.isNotEmpty()) {
                FilterKindCaption("Recurring costs")
                costs.forEach { cost ->
                    DropdownMenuItem(
                        text = { Text(cost.name) },
                        onClick = {
                            onSelect(RecurringFilter(RecurringFilterKind.COST, cost.id))
                            expanded = false
                        },
                    )
                }
            }
            if (incomes.isNotEmpty()) {
                FilterKindCaption("Recurring incomes")
                incomes.forEach { income ->
                    DropdownMenuItem(
                        text = { Text(income.name) },
                        onClick = {
                            onSelect(RecurringFilter(RecurringFilterKind.INCOME, income.id))
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

/** The Recurring filter menu's kind caption (web issue #86): the web
 * select's optgroup label, marking which kind the rows below belong to — a
 * Recurring Cost and a Recurring Income may share a name. A caption shows
 * only above a non-empty group, so an empty menu holds just "All
 * transactions". */
@Composable
private fun FilterKindCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryFilterField(
    selected: CategoryDto?,
    categories: List<CategoryDto>,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.let { categoryFilterLabel(it.name, it.icon) } ?: "All categories",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Category") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All categories") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(categoryFilterLabel(category.name, category.icon)) },
                    onClick = {
                        onSelect(category.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A From/To date bound as a tappable field opening a Material date picker;
 * "Clear" un-sets the bound, "Any date" means no bound (the placeholder). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFilterField(
    label: String,
    value: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedTextField(
            value = value ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("Any date") },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            colors = OutlinedTextFieldDefaults.colors(
                // Disabled but styled like an enabled field: the tap goes to
                // the overlay, not to the field.
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { pickerOpen = true },
        )
    }

    if (pickerOpen) {
        val initialMillis = value
            ?.let { runCatching { Dates.parseApiDay(it).toEpochDay() * MILLIS_PER_DAY }.getOrNull() }
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            val day = Instant.ofEpochMilli(millis).atZone(Dates.rome).toLocalDate()
                            onSelect(Dates.toApiDay(day))
                        }
                        pickerOpen = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                Row {
                    if (value != null) {
                        TextButton(
                            onClick = {
                                onSelect(null)
                                pickerOpen = false
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                    TextButton(onClick = { pickerOpen = false }) { Text("Cancel") }
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/**
 * One ledger row, newest-first order from the API. Frozen-Wallet rows (and
 * Opening Balances) render read-only — dimmed, with no entry point; the
 * edit/delete entry points ticket #20 adds go only on `isEditable` rows.
 */
@Composable
private fun TransactionRow(
    transaction: TransactionDto,
    state: TransactionsViewModel.UiState,
    onOpenEdit: () -> Unit,
) {
    val categoryName = transaction.category_id
        ?.let { id -> state.categories.find { it.id == id }?.name }
    val editable = isEditable(transaction, state.wallets)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (editable) 1f else 0.7f)
            .clickable(enabled = editable, onClick = onOpenEdit),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transactionTitle(transaction, categoryName),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val location = if (hasLocation(transaction)) " · 📍" else ""
                Text(
                    text = "${transaction.date} · ${walletLabel(transaction, state.wallets)}$location",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = signedAmount(transaction),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun FrozenBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The post-write Cash negative-balance banner (ticket #20): amber, like the
 * web app's, and cleared or replaced only by the next write. */
@Composable
private fun WarningBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = AMBER_50,
        border = BorderStroke(1.dp, AMBER_200),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = AMBER_700,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The infinite-scroll sentinel: entering composition (scrolling into view)
 * fetches the next page; a failed fetch shows its error with a Retry and
 * never auto-retries. */
@Composable
private fun LoadMoreSentinel(
    loading: Boolean,
    error: String?,
    onAppear: () -> Unit,
    onRetry: () -> Unit,
) {
    LaunchedEffect(Unit) { onAppear() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Text(
                text = "Loading more…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

/** Write the exported workbook to the app's cache under the name the
 * backend chose and return its FileProvider Uri for the share sheet, or
 * null when the write failed. The cache lives in the app's private
 * storage; the provider grants the receiving app one read. */
private fun cacheExportFile(context: Context, export: ExportFile): Uri? = try {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(directory, export.filename)
    file.writeBytes(export.content)
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (_: Exception) {
    null
}

/** The .xlsx content type: the export's MIME, like the import picker's
 * accepted set. */
private const val EXPORT_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Open a built maps link (ticket #29): the URL is the client-built Google
 * Maps search link (CONTEXT.md — never stored as text), handed to the
 * system — the Google Maps app when installed, the browser otherwise, like
 * the web's target=_blank anchor. A device with no handler silently
 * ignores the tap. */
private fun openMapLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private const val MILLIS_PER_DAY = 86_400_000L