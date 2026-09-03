package com.budjetame.android.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.budjetame.android.ui.theme.Slate400

/**
 * The web rows' trailing ✎ (web issues #93/#94, ADR-0004 anatomy): the
 * Wallet and Category cards' sibling Edit control — a 36 dp round visual
 * on a 40 dp touch target, slate-400, its aria-label ("Edit <name>") as
 * the content description. Opens the row's edit modal — never navigates,
 * and never fires the card's own ledger jump.
 */
@Composable
fun RowEditButton(
    name: String,
    onEdit: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(start = 4.dp, end = 6.dp)
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onEdit),
    ) {
        Icon(
            imageVector = Icons.Outlined.Edit,
            contentDescription = "Edit $name",
            tint = Slate400,
            modifier = Modifier.size(20.dp),
        )
    }
}
