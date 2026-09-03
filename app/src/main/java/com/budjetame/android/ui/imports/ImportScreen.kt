package com.budjetame.android.ui.imports

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowDto
import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.ui.categories.CategoryModal
import com.budjetame.android.ui.transactions.descriptionText
import com.budjetame.android.ui.wallets.WalletModal
import com.budjetame.android.util.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The web ImportScreen's Tailwind palette, ported for the row cards and the
// sticky confirm bar: Ready rows read emerald, Duplicates amber, Problems
// red (web issues #42/#46).
private val EMERALD_50 = Color(0xFFECFDF5)
private val EMERALD_200 = Color(0xFFA7F3D0)
private val EMERALD_700 = Color(0xFF047857)
private val EMERALD_800 = Color(0xFF065F46)
private val AMBER_50 = Color(0xFFFFFBEB)
private val AMBER_200 = Color(0xFFFDE68A)
private val AMBER_300 = Color(0xFFFCD34D)
private val AMBER_700 = Color(0xFFB45309)
private val AMBER_800 = Color(0xFF92400E)
private val RED_50 = Color(0xFFFEF2F2)
private val RED_200 = Color(0xFFFECACA)
private val RED_700 = Color(0xFFB91C1C)

/**
 * The bulk Import flow (ticket #26, web issue #43/#46): pick a .csv/.xlsx
 * file against the fixed template through the system file picker, see the
 * extracted rows validated — duplicates in amber, problems in red, every
 * Ready row auto-selected — then confirm; nothing reaches the database
 * before that confirmation. The Import Draft itself lives in the tab's
 * ViewModel, so it survives tab switches; this screen only renders and
 * drives it. Verification opens the row editor for any row and flips its
 * status inline on save; the confirm bar below the list keeps the counts
 * and the Import button visible while long files scroll.
 */
@Composable
fun ImportScreen(
    draft: ImportDraft,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    viewModel: ImportViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The picker's result — a document Uri — is read into memory here (the
    // ViewModel stays free of Android framework types): the display name
    // and the bytes travel to the draft.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    readPickedFile(context.contentResolver, uri)
                }
                if (picked == null) {
                    viewModel.onFilePicked(null, null)
                } else {
                    viewModel.onFilePicked(picked.first, picked.second)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = "Import",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = if (draft.phase == ImportPhase.DONE) viewModel::done else viewModel::cancel,
            ) {
                Text(if (draft.phase == ImportPhase.DONE) "Back" else "Cancel")
            }
        }

        // The on-resume re-check (web issue #76, ticket #27): the Draft
        // survives tab switches in the tab's ViewModel (ADR-0003), so the
        // Preview "resumes" when this screen re-enters composition — the
        // user returns to the Transactions tab — and every re-entry
        // re-checks the live Preview's problem rows in one batch against
        // the Account's current Wallets and Categories: a Wallet or
        // Category created on another tab flips the rows that waited on
        // it. The ViewModel re-checks nothing while the flow is not in a
        // live Preview (the flow opens in the pick phase, so the first
        // entry never re-checks).
        LaunchedEffect(Unit) {
            viewModel.recheckProblems()
        }

        when (draft.phase) {
            ImportPhase.PICK -> PickPhase(
                draft = draft,
                onChooseFile = { filePicker.launch(IMPORT_MIME_TYPES) },
                onRead = viewModel::readFile,
                modifier = Modifier.weight(1f),
            )
            ImportPhase.PREVIEW -> draft.preview?.let { preview ->
                PreviewPhase(
                    draft = draft,
                    preview = preview,
                    onToggle = viewModel::toggle,
                    onEdit = viewModel::openEditor,
                    onPickAgain = viewModel::pickAgain,
                    onConfirm = viewModel::confirm,
                    modifier = Modifier.weight(1f),
                )
            }
            ImportPhase.DONE -> DonePhase(
                draft = draft,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // Verification's row editor (web issue #46), open for exactly one
    // Preview row.
    val editing = draft.editingRow?.let { rowNumber ->
        draft.preview?.rows?.find { it.row == rowNumber }
    }
    if (editing != null) {
        ImportRowEditor(
            row = editing,
            wallets = wallets,
            categories = categories,
            saving = draft.editorSaving,
            error = draft.editorError,
            onSave = viewModel::saveRowEdit,
            onClose = viewModel::closeEditor,
            onAddWallet = viewModel::onRowWalletAdd,
            walletToSelect = draft.rowWalletToSelect,
            onAddCategory = viewModel::onRowCategoryAdd,
            categoryToSelect = draft.rowCategoryToSelect,
        )
    }

    // Inline entity creation (ADR-0013/0014, ticket #27): the entity's
    // create form, stacked on the row editor. Composed after it, its dialog
    // window renders on top; a Cancel or back press closes only this one —
    // the row editor's draft survives below. The entity is created for real
    // through the same endpoints the Wallets/Categories screens use
    // (ADR-0014), its name is reported back to the editor's originating
    // field, and the problem rows that waited on it re-validate in a batch.
    draft.rowWalletCreate?.let { create ->
        WalletModal(
            modal = create.modal,
            allowedTypes = create.allowedTypes,
            onNameChange = viewModel::onRowWalletCreateNameChange,
            onTypeChange = viewModel::onRowWalletCreateTypeChange,
            onOpeningBalanceChange = viewModel::onRowWalletCreateOpeningBalanceChange,
            onSubmit = viewModel::submitRowWalletCreate,
            onFreeze = {}, // Create-only: the freeze section never renders.
            onClose = viewModel::cancelRowWalletCreate,
        )
    }

    draft.rowCategoryCreate?.let { create ->
        CategoryModal(
            modal = create.modal,
            lockedType = create.lockedType,
            onNameChange = viewModel::onRowCategoryCreateNameChange,
            onTypeChange = {}, // Locked: the Type selector never renders.
            onIconChange = viewModel::onRowCategoryCreateIconChange,
            onColorChange = viewModel::onRowCategoryCreateColorChange,
            onSubmit = viewModel::submitRowCategoryCreate,
            onMerge = {}, // Create-only: the merge/delete sections never render.
            onCancelMerge = {},
            onDelete = {},
            onClose = viewModel::cancelRowCategoryCreate,
        )
    }
}

/** The pick phase (web issue #43): the template's description, the file
 * picker, the picked file's line, and "Read and validate" — the only
 * gateway into the Preview. Nothing is written until the Preview's
 * confirmation. */
@Composable
private fun PickPhase(
    draft: ImportDraft,
    onChooseFile: () -> Unit,
    onRead: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                append("Upload a ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(".csv") }
                append(" or ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(".xlsx") }
                append(" file with the fixed template: one flat sheet, columns ")
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                    append(
                        "date, type, amount, wallet, source wallet, destination wallet, " +
                            "category, description, location",
                    )
                }
                append(". Nothing is written until you confirm the preview.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onChooseFile,
            enabled = !draft.busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text("Choose file")
        }
        draft.fileName?.let { name ->
            Text(
                text = pickedFileLine(name, draft.fileSizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        draft.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        Button(
            onClick = onRead,
            enabled = draft.fileName != null && !draft.busy,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text(if (draft.busy) "Reading file…" else "Read and validate")
        }
    }
}

/** The review step (web issues #42/#46): every row's verdict as a card —
 * Ready rows carry a selection checkbox, any row opens the Verification
 * editor on tap — over the sticky confirm bar with the live counts and the
 * Import button. "Pick another file" abandons this Preview for a fresh
 * pick; the error line carries a failed confirm's detail. */
@Composable
private fun PreviewPhase(
    draft: ImportDraft,
    preview: ImportPreviewDto,
    onToggle: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onPickAgain: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counts = previewCounts(preview)
    val confirmable = draft.selected.size
    Column(modifier = modifier.fillMaxWidth()) {
        draft.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            OutlinedButton(onClick = onPickAgain, enabled = !draft.busy) {
                Text("Pick another file")
            }
        }
        if (preview.rows.isEmpty()) {
            Text(
                text = "No data rows found in this file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(preview.rows, key = { it.row }) { row ->
                    ImportRowCard(
                        row = row,
                        selected = draft.selected.contains(row.row),
                        onToggle = { onToggle(row.row) },
                        onEdit = { onEdit(row.row) },
                    )
                }
            }
        }
        ConfirmBar(
            ready = counts.ready,
            duplicates = counts.duplicates,
            problems = counts.problems,
            confirmable = confirmable,
            busy = draft.busy,
            onConfirm = onConfirm,
        )
    }
}

/** One Preview row (web issues #42/#46): a checkbox toggles the selection
 * of Ready rows — Duplicates and Problems are never selectable — and the
 * card opens the Verification editor for any row, ready, duplicate, or
 * problem. The border and badge speak the row's verdict; a Problem's
 * message and a Duplicate's explanation sit under the fields. */
@Composable
private fun ImportRowCard(
    row: ImportRowDto,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    val checkable = row.status == ImportRowStatus.OK
    val border = when (row.status) {
        ImportRowStatus.OK -> EMERALD_200
        ImportRowStatus.DUPLICATE -> AMBER_300
        ImportRowStatus.ERROR -> RED_200
    }
    val background = when (row.status) {
        ImportRowStatus.OK -> MaterialTheme.colorScheme.surface
        ImportRowStatus.DUPLICATE -> AMBER_50
        ImportRowStatus.ERROR -> RED_50
    }
    val badgeColor = when (row.status) {
        ImportRowStatus.OK -> EMERALD_700
        ImportRowStatus.DUPLICATE -> AMBER_800
        ImportRowStatus.ERROR -> RED_700
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = background,
        border = BorderStroke(1.dp, border),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Checkbox(
                checked = checkable && selected,
                onCheckedChange = { onToggle() },
                enabled = checkable,
                modifier = Modifier.padding(top = 4.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onEdit)
                    .padding(start = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = importRowTitleLine(row.date, row.type),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = importStatusWord(row.status),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = badgeColor,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                descriptionText(row.description)?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = row.amount?.let(Money::formatEuros) ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 2.dp),
                )
                val walletLine = buildString {
                    append(importWalletLine(row.type, row.wallet, row.source_wallet, row.destination_wallet))
                    if (row.category != null && row.category.isNotEmpty()) append(" · ${row.category}")
                    append(importLocationSuffix(row.latitude, row.longitude))
                }
                Text(
                    text = walletLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                when (row.status) {
                    ImportRowStatus.ERROR -> row.error?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = RED_700,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    ImportRowStatus.DUPLICATE -> Text(
                        text = "Already in the database or repeated in this file — this row will be skipped.",
                        style = MaterialTheme.typography.labelSmall,
                        color = AMBER_800,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    ImportRowStatus.OK -> Unit
                }
            }
        }
    }
}

/** The sticky confirm bar (web issue #42): the live counts — colored per
 * verdict — and the Import button, always visible under the scrolling row
 * list. The button speaks the selection: "Nothing to import" at zero,
 * "Import N rows" otherwise, "Importing…" while the insert is in flight. */
@Composable
private fun ConfirmBar(
    ready: Int,
    duplicates: Int,
    problems: Int,
    confirmable: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = EMERALD_700)) { append("$ready ready") }
                    append(" · ")
                    withStyle(SpanStyle(color = AMBER_700)) {
                        append(if (duplicates == 1) "1 duplicate" else "$duplicates duplicates")
                    }
                    append(" · ")
                    withStyle(SpanStyle(color = RED_700)) {
                        append(if (problems == 1) "1 problem" else "$problems problems")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                enabled = confirmable > 0 && !busy,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(importButtonText(confirmable, busy))
            }
        }
    }
}

/** The successful-import report: what was imported, that history now
 * reflects it, and the amber note when the import made a Cash Wallet
 * negative. Back returns to the ledger. */
@Composable
private fun DonePhase(
    draft: ImportDraft,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (draft.createdWithWarning) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AMBER_50,
                border = BorderStroke(1.dp, AMBER_200),
            ) {
                Text(
                    text = "Imported — but the import made a Cash wallet negative.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AMBER_700,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = EMERALD_50,
            border = BorderStroke(1.dp, EMERALD_200),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Imported ${draft.imported} transaction" +
                        (if (draft.imported == 1) "." else "s."),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = EMERALD_800,
                )
                Text(
                    text = "They are now in your history; balances and the dashboard reflect them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = EMERALD_700,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Read a picked document into memory: its display name and bytes, or null
 * when the read failed (the pick phase then surfaces its error). */
private fun readPickedFile(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray>? = try {
    val name = resolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        } else {
            null
        }
    }?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: return null
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    name to bytes
} catch (_: Exception) {
    null
}

/** The system file picker's accepted types: .csv and .xlsx (plus the
 * generic octet-stream some providers label them with — the backend
 * rejects anything that is not .csv/.xlsx with a clear message). */
private val IMPORT_MIME_TYPES = arrayOf(
    "text/csv",
    "text/comma-separated-values",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)
