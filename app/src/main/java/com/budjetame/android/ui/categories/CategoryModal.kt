package com.budjetame.android.ui.categories

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType

// The web app's Tailwind palette, ported for the merge offer and delete
// confirmation (CategoryForm.tsx).
private val AMBER_50 = Color(0xFFFFFBEB)
private val AMBER_300 = Color(0xFFFCD34D)
private val AMBER_600 = Color(0xFFD97706)
private val AMBER_800 = Color(0xFF92400E)
private val SLATE_800 = Color(0xFF1E293B)
private val RED_200 = Color(0xFFFECACA)
private val RED_600 = Color(0xFFDC2626)

/**
 * The create/edit/delete Category form inside an AlertDialog (web issue #41).
 * Create and edit share one modal: the Type selector appears only while
 * creating, and the tap-again delete confirmation only while editing. A
 * colliding rename turns the failure into the merge offer (ADR-0007) —
 * "Merge X into Y? N transactions will move" — with the same tap-again
 * confirmation; Cancel merge abandons the offer without saving. Also hosts
 * the Transaction form's inline "New category…" creation (ADR-0013):
 * `lockedType` presets the create form's Type and hides its selector —
 * Expense for an Expense form, Income for an Income form — so the created
 * Category always fits the transaction being recorded. Edit mode never
 * changes Type, so the lock is create-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryModal(
    modal: CategoryModalState,
    lockedType: CategoryType? = null,
    onNameChange: (String) -> Unit,
    onTypeChange: (CategoryType) -> Unit,
    onIconChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMerge: () -> Unit,
    onCancelMerge: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val category = modal.category
    val editing = category != null

    AlertDialog(
        onDismissRequest = { if (!modal.busy) onClose() },
        title = { Text(if (editing) "Edit category" else "New category") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (category != null) {
                    Text(
                        text = "${categoryTypeLabel(category.type)} · type cannot be changed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = modal.name,
                    onValueChange = onNameChange,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Groceries") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("category-name"),
                )

                if (!editing) {
                    if (lockedType != null) {
                        Text(
                            text = "${categoryTypeLabel(lockedType)} · fixed for this form",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    } else {
                        CategoryTypeField(
                            value = modal.type,
                            onSelect = onTypeChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                }

                ColorPicker(
                    selected = modal.color,
                    onSelect = onColorChange,
                    modifier = Modifier.padding(top = 12.dp),
                )

                OutlinedTextField(
                    value = modal.icon,
                    onValueChange = onIconChange,
                    label = { Text("Icon (optional)") },
                    placeholder = { Text("e.g. 🛒") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )

                modal.error?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                val offer = modal.mergeOffer
                if (category != null && offer != null) {
                    MergeOfferSection(
                        category = category,
                        modal = modal,
                        onMerge = onMerge,
                        onCancelMerge = onCancelMerge,
                    )
                }

                if (category != null) {
                    DeleteSection(modal = modal, onDelete = onDelete)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = modal.canSubmit) {
                Text(
                    when {
                        modal.submitting -> "Saving…"
                        editing -> "Save"
                        else -> "Create category"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !modal.busy) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTypeField(
    value: CategoryType,
    onSelect: (CategoryType) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = categoryTypeLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            CategoryType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(categoryTypeLabel(type)) },
                    onClick = {
                        onSelect(type)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The ten preset swatches (web CategoryForm); the selected one is ringed. */
@Composable
private fun ColorPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Color",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        FlowRow(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CATEGORY_PRESET_COLORS.forEach { preset ->
                val isSelected = preset.equals(selected, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(hexColor(preset))
                        .then(
                            if (isSelected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(preset) }
                        .semantics { contentDescription = "Use color $preset" },
                )
            }
        }
    }
}

@Composable
private fun MergeOfferSection(
    category: CategoryDto,
    modal: CategoryModalState,
    onMerge: () -> Unit,
    onCancelMerge: () -> Unit,
) {
    val offer = modal.mergeOffer ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(AMBER_50, RoundedCornerShape(12.dp))
            .border(1.dp, AMBER_300, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "Merge ${category.name} into ${modal.name}? " +
                "${offer.transactionCount} transactions will move — this cannot be undone.",
            style = MaterialTheme.typography.bodySmall,
            color = SLATE_800,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Button(
                onClick = onMerge,
                enabled = !modal.busy,
                colors = if (modal.confirmingMerge) {
                    ButtonDefaults.buttonColors(containerColor = AMBER_600, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AMBER_800)
                },
                border = BorderStroke(1.dp, AMBER_300),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        modal.merging -> "Merging…"
                        modal.confirmingMerge -> "Tap again to confirm"
                        else -> "Merge"
                    },
                )
            }
            TextButton(
                onClick = onCancelMerge,
                enabled = !modal.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel merge")
            }
        }
    }
}

@Composable
private fun DeleteSection(
    modal: CategoryModalState,
    onDelete: () -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
    Text(
        text = "Delete category",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Text(
        text = "Its transactions become uncategorized — no transaction is ever deleted.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Button(
        onClick = onDelete,
        enabled = !modal.busy,
        colors = if (modal.confirmingDelete) {
            ButtonDefaults.buttonColors(containerColor = RED_600, contentColor = Color.White)
        } else {
            ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = RED_600)
        },
        border = if (modal.confirmingDelete) null else BorderStroke(1.dp, RED_200),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Text(
            when {
                modal.deleting -> "Deleting…"
                modal.confirmingDelete -> "Tap again to confirm"
                else -> "Delete category"
            },
        )
    }
}
