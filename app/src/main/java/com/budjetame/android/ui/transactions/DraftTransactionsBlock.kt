package com.budjetame.android.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.DraftTransactionDto
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.ui.theme.Red600
import com.budjetame.android.ui.theme.Slate500

// The web block's tints: indigo-50/60 behind, indigo-200 border, indigo-900
// heading, indigo-700 hint; the missing marks are amber-100 on amber-800.
private val INDIGO_50 = Color(0xFFEEF2FF)
private val INDIGO_200 = Color(0xFFC7D2FE)
private val INDIGO_700 = Color(0xFF4338CA)
private val INDIGO_900 = Color(0xFF312E81)
private val AMBER_100 = Color(0xFFFEF3C7)
private val AMBER_800 = Color(0xFF92400E)

/**
 * The pinned Drafts block (ADR-0036 in the web repo, ticket #67): the
 * Account's Draft Transactions above the ledger, one row per Draft with the
 * same columns as a Transaction row — title, date · wallet, amount — and
 * each missing field visibly marked. Three actions per row: Approve, Edit,
 * Discard. The block only reports the taps; the ViewModel owns the calls
 * and the form. Rendered only when there is at least one Draft.
 */
@Composable
internal fun DraftTransactionsBlock(
    drafts: List<DraftTransactionDto>,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    error: String?,
    onApprove: (DraftTransactionDto) -> Unit,
    onEdit: (DraftTransactionDto) -> Unit,
    onDiscard: (DraftTransactionDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drafts.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = INDIGO_50,
        border = BorderStroke(1.dp, INDIGO_200),
        modifier = modifier
            .fillMaxWidth()
            .testTag("drafts-block"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Drafts (${drafts.size})",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = INDIGO_900,
            )
            Text(
                text = "Not yet transactions — they count for nothing until approved.",
                fontSize = 12.sp,
                color = INDIGO_700,
            )
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = Red600)
            }
            drafts.forEach { draft ->
                DraftRow(
                    draft = draft,
                    wallets = wallets,
                    categories = categories,
                    onApprove = { onApprove(draft) },
                    onEdit = { onEdit(draft) },
                    onDiscard = { onDiscard(draft) },
                )
            }
        }
    }
}

@Composable
private fun DraftRow(
    draft: DraftTransactionDto,
    wallets: List<WalletDto>,
    categories: List<CategoryDto>,
    onApprove: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
) {
    val categoryName = draft.category_id?.let { id -> categories.find { it.id == id }?.name }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("draft-row-${draft.id}"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = draftTitle(draft, categoryName),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    DraftSubtitle(draft = draft, wallets = wallets)
                }
                val amount = draftSignedAmount(draft)
                if (amount == null) {
                    MissingMark("Amount missing", modifier = Modifier.padding(start = 8.dp))
                } else {
                    Text(
                        text = amount,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Button(
                    onClick = onApprove,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = ACTION_PADDING,
                    modifier = Modifier.height(32.dp),
                ) { Text("Approve", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = ACTION_PADDING,
                    modifier = Modifier.height(32.dp),
                ) { Text("Edit", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onDiscard,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = ACTION_PADDING,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red600),
                    modifier = Modifier.height(32.dp),
                ) { Text("Discard", fontSize = 12.sp) }
            }
        }
    }
}

private val ACTION_PADDING = PaddingValues(horizontal = 12.dp)

/** The row's "date · wallet" line, each missing part an amber mark. A
 * referenced Wallet the list does not know reads as missing — Approve
 * will say the same. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftSubtitle(draft: DraftTransactionDto, wallets: List<WalletDto>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        if (draft.type == null) MissingMark("Type missing")
        val date = draft.date
        if (date == null) MissingMark("Date missing") else SubtitleText(date)
        SubtitleText("·")
        if (draftIsTransfer(draft)) {
            WalletPart(wallets, draft.source_wallet_id, "Source missing")
            SubtitleText("→")
            WalletPart(wallets, draft.destination_wallet_id, "Destination missing")
        } else {
            WalletPart(wallets, draft.wallet_id, "Wallet missing")
        }
    }
}

@Composable
private fun WalletPart(wallets: List<WalletDto>, walletId: Int?, missing: String) {
    val name = walletId?.let { id -> wallets.find { it.id == id }?.name }
    if (name == null) MissingMark(missing) else SubtitleText(name)
}

@Composable
private fun SubtitleText(text: String) {
    Text(text = text, fontSize = 12.sp, color = Slate500, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A missing field's mark (the web's amber pill). */
@Composable
private fun MissingMark(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(4.dp), color = AMBER_100, modifier = modifier) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = AMBER_800,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
