package com.budjetame.android.ui.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.category.CategoryGateway

/**
 * The Categories tab (ticket #16): two sections — Expenses and Incomes —
 * each sorted A→Z case-insensitively, with a search bar that filters both
 * sections live. Creating, editing, deleting, and the rename-collision merge
 * flow live in the shared modal.
 */
@Composable
fun CategoriesScreen(categories: CategoryGateway) {
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
            else -> CategoriesList(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
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
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onNewCategory) {
            Text("New category")
        }
    }
}

@Composable
private fun CategoriesList(
    state: CategoriesViewModel.UiState,
    viewModel: CategoriesViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            placeholder = { Text("Search categories…") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
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
                items(section.items, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        onClick = { viewModel.openEdit(category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(category: CategoryDto, onClick: () -> Unit) {
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
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = categoryTypeLabel(category.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Parse a "#rrggbb" hex color (the API's color vocabulary). */
internal fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

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
