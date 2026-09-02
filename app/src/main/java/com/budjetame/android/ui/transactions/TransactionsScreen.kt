package com.budjetame.android.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.util.Dates
import java.time.Instant

private val AMBER_50 = Color(0xFFFFFBEB)
private val AMBER_200 = Color(0xFFFDE68A)
private val AMBER_700 = Color(0xFFB45309)

/**
 * The Transactions tab (ticket #19): the ledger read path — a newest-first
 * list with cursor paging (50 per page) and infinite scroll, the collapsible
 * filter bar (wallet, frozen ones included and marked, date range,
 * category), and the debounced description search. Rows on Frozen Wallets
 * render read-only (dimmed, no entry points — the edit/delete entry points
 * themselves land with ticket #20's forms and honor `isEditable`).
 */
@Composable
fun TransactionsScreen(
    transactions: TransactionGateway,
    wallets: WalletGateway,
    categories: CategoryGateway,
) {
    val viewModel: TransactionsViewModel = viewModel {
        TransactionsViewModel(transactions, wallets, categories)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Transactions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = viewModel::openCreate,
                enabled = !state.loading && state.loadError == null,
            ) {
                Text("New transaction")
            }
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
            onTypeChange = viewModel::onTypeChange,
            onAmountChange = viewModel::onAmountChange,
            onDateChange = viewModel::onDateChange,
            onWalletChange = viewModel::onWalletChange,
            onSourceWalletChange = viewModel::onSourceWalletChange,
            onDestinationWalletChange = viewModel::onDestinationWalletChange,
            onCategoryChange = viewModel::onCategoryChange,
            onDescriptionChange = viewModel::onDescriptionChange,
            onSubmit = viewModel::submit,
            onDelete = viewModel::onDeleteTap,
            onClose = viewModel::closeModal,
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
        item(key = "all-transactions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All transactions",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = viewModel::toggleFilters) {
                    Text(if (state.filtersOpen) "Filters ▾" else "Filters ▸")
                }
            }
        }

        if (!state.ledgerEmpty) {
            item(key = "search") {
                SearchField(
                    value = state.search,
                    onValueChange = viewModel::onSearchChange,
                    modifier = Modifier.padding(top = 8.dp),
                )
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
        modifier = modifier.fillMaxWidth(),
    )
}

/** The collapsible filter bar (closed by default): wallet, date range,
 * category — every change refetches the first page with it applied. */
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
            CategoryFilterField(
                selected = state.categories.find { it.id == state.filterCategoryId },
                categories = state.categories,
                onSelect = viewModel::onFilterCategoryChange,
            )
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

private const val MILLIS_PER_DAY = 86_400_000L
