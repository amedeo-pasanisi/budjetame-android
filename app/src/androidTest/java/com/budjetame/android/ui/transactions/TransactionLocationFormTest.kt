package com.budjetame.android.ui.transactions

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.WalletDto
import com.budjetame.android.data.api.WalletType
import com.budjetame.android.data.transaction.LatLng
import com.budjetame.android.data.transaction.Place
import com.budjetame.android.data.transaction.mapLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Transaction form's Location section (ticket #29), driven with a fake
 * picker behind the provider seam (ADR-0004): the attached-location chip
 * with the Place's name and the client-built maps link, the Remove /
 * Add / Change actions, the locating state and the inline GPS failure line,
 * and the picker round-trip — a pick lands the location (and its Place) and
 * closes the picker; the Cancel leaves the form's location untouched. The
 * real seam content (osmdroid / Google Maps) is not drivable in tests — the
 * seam's contract is: pickers report through the same onPick callback the
 * fake drives here.
 */
@RunWith(AndroidJUnit4::class)
class TransactionLocationFormTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val cash = WalletDto(1, "Cash", WalletType.CASH, "100.00", false, "2026-08-01T10:00:00Z")

    private fun setForm(
        initial: TransactionsViewModel.ModalState,
        onOpenMapLink: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            var modal by remember { mutableStateOf(initial) }
            TransactionModal(
                modal = modal,
                wallets = listOf(cash),
                categories = listOf(
                    CategoryDto(1, "Food", CategoryType.EXPENSE, "🍕", "#ef4444", "2026-08-01T10:00:00Z"),
                ),
                onTypeChange = { modal = modal.copy(type = it) },
                onAmountChange = { modal = modal.copy(amount = it) },
                onDateChange = { modal = modal.copy(date = it) },
                onWalletChange = { modal = modal.copy(walletId = it) },
                onSourceWalletChange = { modal = modal.copy(sourceWalletId = it) },
                onDestinationWalletChange = { modal = modal.copy(destinationWalletId = it) },
                onCategoryChange = { modal = modal.copy(categoryId = it) },
                onDescriptionChange = { modal = modal.copy(description = it) },
                onSubmit = {},
                onDelete = {},
                onClose = {},
                onLocationPick = { picked, pickedPlace ->
                    modal = modal.copy(location = picked, place = pickedPlace, showingPicker = false)
                },
                onRemoveLocation = {
                    modal = modal.copy(location = null, place = null, showingPicker = false)
                },
                onUseMyLocation = { modal = modal.copy(locating = false) },
                onOpenLocationPicker = { modal = modal.copy(showingPicker = true) },
                onCloseLocationPicker = { modal = modal.copy(showingPicker = false) },
                onOpenMapLink = onOpenMapLink,
                // The fake seam content: reports a canned pick through the
                // same contract every real provider implements.
                mapPicker = { position, onPick, onCancel ->
                    Column {
                        Text("Fake map centered on ${position?.lat}")
                        Button(onClick = { onPick(LatLng(41.89, 12.49), Place("Colosseo", "ChIJxyz")) }) {
                            Text("Pick Colosseo")
                        }
                        TextButton(onClick = onCancel) { Text("Cancel picker") }
                    }
                },
            )
        }
    }

    @Test
    fun a_picked_location_shows_its_place_name_link_and_remove() {
        var opened: String? = null
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                location = LatLng(41.9028, 12.4964),
                place = Place("Esselunga", "ChIJN1t_tDeuEmsRUsoyG83frY4"),
            ),
            onOpenMapLink = { opened = it },
        )

        // The chip names the Place (ADR-0005) and offers the maps link.
        composeRule.onNodeWithText("📍 Esselunga", substring = true).assertIsDisplayed()
        // The maps link is built client-side from the Place's id and the
        // coordinates — never stored as text.
        composeRule.onNodeWithTag("tx-location-link").performClick()
        assertEquals(
            mapLink(LatLng(41.9028, 12.4964), Place("Esselunga", "ChIJN1t_tDeuEmsRUsoyG83frY4")),
            opened,
        )
        // A located form's open button reads "Change location".
        composeRule.onNodeWithText("Change location").assertIsDisplayed()
    }

    @Test
    fun a_coordinates_only_location_shows_the_coordinates_in_the_chip() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                location = LatLng(41.9028, 12.4964),
            ),
        )

        // No Place: the chip falls back to the coordinate pair.
        composeRule.onNodeWithText("📍 41.9028, 12.4964").assertIsDisplayed()
    }

    @Test
    fun removing_a_location_shows_the_no_location_state() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                location = LatLng(41.9028, 12.4964),
                place = Place("Esselunga", "ChIJabc"),
            ),
        )

        composeRule.onNodeWithTag("tx-location-remove").performClick()

        composeRule.onNodeWithText("No location attached.").assertIsDisplayed()
        composeRule.onNodeWithText("Add location").assertIsDisplayed()
    }

    @Test
    fun the_gps_button_reads_locating_and_disables_while_the_lookup_runs() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                locating = true,
            ),
        )

        composeRule.onNodeWithText("Locating…").assertIsDisplayed()
        composeRule.onNodeWithTag("tx-location-gps").assertIsNotEnabled()
    }

    @Test
    fun the_inline_gps_failure_message_shows_under_the_buttons() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                gpsError = TransactionsViewModel.GPS_ERROR_TEXT,
            ),
        )

        composeRule.onNodeWithText(TransactionsViewModel.GPS_ERROR_TEXT).assertIsDisplayed()
    }

    @Test
    fun a_picker_pick_lands_the_location_and_place_and_closes_the_picker() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                showingPicker = true,
            ),
        )

        // The fake seam content hosts the pick.
        composeRule.onNodeWithText("Fake map centered on null").assertIsDisplayed()
        composeRule.onNodeWithText("Pick Colosseo").performClick()

        // The pick landed and the picker closed: the chip shows the Place.
        composeRule.onNodeWithText("📍 Colosseo", substring = true).assertIsDisplayed()
        assertFalse(
            composeRule.onAllNodesWithText("Fake map centered on null").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun canceling_the_picker_leaves_the_form_location_untouched() {
        setForm(
            TransactionsViewModel.ModalState(
                type = TransactionType.EXPENSE,
                amount = "5.00",
                date = "2026-08-01",
                walletId = 1,
                location = LatLng(41.9028, 12.4964),
                place = Place("Esselunga", "ChIJabc"),
                showingPicker = true,
            ),
        )

        composeRule.onNodeWithText("Fake map centered on 41.9028").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel picker").performClick()

        composeRule.onNodeWithText("📍 Esselunga", substring = true).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Change location").fetchSemanticsNodes().isNotEmpty())
    }
}
