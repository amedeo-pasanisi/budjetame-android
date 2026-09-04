package com.budjetame.android.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.ui.common.LoadErrorBody
import com.budjetame.android.ui.common.RowEditButton
import com.budjetame.android.ui.common.MessageBody
import com.budjetame.android.ui.theme.Slate500
import com.budjetame.android.ui.theme.Slate700

/**
 * The Categories tab (ticket #16, ADR-0004 anatomy): two sections —
 * Expenses and Incomes — each sorted A→Z case-insensitively, under a
 * pinned search bar that filters both sections live. A row is a tap
 * surface with a sibling trailing ✎ button inside one card (web issue
 * #94): the tap surface (color dot + name + type) sends the ledger jump —
 * the Transactions tab opens pre-filtered to that Category, covering
 * Expense and Income alike — and the trailing ✎ opens the edit modal
 * (rename/delete/merge, ADR-0007). The old whole-row edit semantics moved
 * here.
 */
@Composable
fun CategoriesScreen(
    categories: CategoryGateway,
    /** Send a ledger jump (ADR-0004): open the Transactions tab with the
     * ledger pre-filtered to one Category. Fired by the whole-row tap
     * surface (web issue #94). */
    onLedgerJump: (LedgerJump) -> Unit = {},
) {
    val viewModel: CategoriesViewModel = viewModel { CategoriesViewModel(categories) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        CategoriesHeader(onNewCategory = viewModel::openCreate)

        val loadError = state.loadError
        when {
            state.loading -> MessageBody(
                text = "Loading categories…",
                modifier = Modifier.weight(1f),
            )
            loadError != null -> LoadErrorBody(
                message = loadError,
                onRetry = viewModel::retry,
                modifier = Modifier.weight(1f),
            )
            state.categories.isEmpty() -> MessageBody(
                text = "No categories yet. Add one to start grouping your transactions.",
                modifier = Modifier.weight(1f),
            )
            else -> CategoriesList(
                state = state,
                viewModel = viewModel,
                onLedgerJump = onLedgerJump,
                modifier = Modifier.weight(1f),
            )
        }
    }

    state.modal?.let { modal ->
        CategoryModal(
            modal = modal,
            onNameChange = viewModel::onNameChange,
            onTypeChange = viewModel::onTypeChange,
            onIconChange = viewModel::onIconChange,
            onColorChange = viewModel::onColorChange,
            onSubmit = viewModel::submit,
            onMerge = viewModel::onMergeTap,
            onCancelMerge = viewModel::cancelMerge,
            onDelete = viewModel::onDeleteTap,
            onClose = viewModel::closeModal,
        )
    }
}

@Composable
private fun CategoriesHeader(onNewCategory: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Categories",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onNewCategory,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            Text("New category")
        }
    }
}

@Composable
private fun CategoriesList(
    state: CategoriesViewModel.UiState,
    viewModel: CategoriesViewModel,
    onLedgerJump: (LedgerJump) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // The pinned search bar (ticket #44): fixed chrome under the
        // header, like the Transactions tab's toolbar — only the records
        // scroll.
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Search categories…") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Slate500,
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                // The shared below-header gap (ticket #44): 8 dp of the
                // header's own padding + 4 here = the 12 dp the web's mt-3
                // gives every tab.
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )

        if (state.sections.all { it.items.isEmpty() }) {
            MessageBody(
                text = "No categories match your search.",
                modifier = Modifier.weight(1f),
            )
            return
        }

        val visibleSections = state.sections.filter { it.items.isNotEmpty() }
        LazyColumn(
            modifier = Modifier.weight(1f),
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
                items(section.items, key = { it.id }) { category ->
                    CategoryCard(
                        category = category,
                        onOpenLedger = { onLedgerJump(LedgerJump.Category(category.id)) },
                        onEdit = { viewModel.openEdit(category) },
                    )
                }
            }
        }
    }
}

/**
 * One Category row's card (ADR-0004 anatomy): the tap surface (color dot +
 * name + type) sends the ledger jump; the trailing ✎ Edit button is its
 * sibling, never nested inside the tap surface.
 */
@Composable
private fun CategoryCard(
    category: CategoryDto,
    onOpenLedger: () -> Unit,
    onEdit: () -> Unit,
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
            .padding(horizontal = 6.dp, vertical = 4.dp),
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
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(hexColor(category.color)),
                ) {
                    Text(
                        text = category.icon ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = categoryTypeLabel(category.type),
                        fontSize = 12.sp,
                        color = Slate500,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowEditButton(name = category.name, onEdit = onEdit)
        }
    }
}


/** Parse a "#rrggbb" hex color (the API's color vocabulary). */
internal fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))
