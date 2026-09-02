package com.budjetame.android.ui.maps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place

/**
 * The map picker's host dialog (ticket #29): a tap-to-pick map in a modal
 * card with the picker's own Cancel — the mobile shape of the web form's
 * inline picker area, which closes on any pick. The seam's content (the
 * provider-selected adapter) fills the card; a pick reports through
 * `onPick` and the caller closes the dialog, exactly like the web's
 * `setShowingPicker(false)`.
 */
@Composable
fun MapPickerDialog(
    position: LatLng?,
    onPick: (LatLng, Place?) -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .height(PICKER_DIALOG_HEIGHT)
                    .padding(16.dp),
            ) {
                MapPickerContent(
                    position = position,
                    onPick = onPick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

/** A tall-enough map on every phone; the dialog's own Cancel bar sits below. */
private val PICKER_DIALOG_HEIGHT = 520.dp
