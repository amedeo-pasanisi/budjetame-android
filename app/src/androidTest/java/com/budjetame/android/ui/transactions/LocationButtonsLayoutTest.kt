package com.budjetame.android.ui.transactions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.transaction.LatLng
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Location section button pair's width behavior (ticket #37): the two
 * OutlinedButtons keep their natural width inside a FlowRow with 8dp
 * spacing, so 'Add location' / 'Change location' and 'Use my location'
 * share one row at a 360dp dialog's width at the default font scale, and
 * a label can never be squeezed below one line — when the pair does not
 * fit at a larger scale, whole buttons wrap onto further lines instead of
 * breaking mid-word. The buttons are driven directly at a fixed width and
 * font scale (the same harness the TypeSelectorLayoutTest and the
 * header-fit test in TransactionsChromeTest use), since the dialog chrome
 * cannot be resized or re-scaled from a test: the assertions hold for the
 * shared LocationButtons wherever it renders — the Transaction modal's
 * Location section at any dialog width. Label heights are compared
 * against the M3 labelLarge line box (20sp per line at the forced scale):
 * one line measures 20sp × scale, a wrapped label at least twice that, so
 * the one-line threshold sits between the two and never depends on glyph
 * metrics. Compose only ever breaks a Text at word boundaries, never
 * mid-word, so an intact single-line height is the whole-button-wrap
 * proof; at the extreme scale where even one button cannot fit its label
 * on a line, the label takes two lines — the case the labels'
 * TextAlign.Center safety net serves (line placement itself is not
 * exposed to UI tests, so the centeredness lives in the composable's
 * textAlign, pinned here by the two-line scenario existing at all).
 */
@RunWith(AndroidJUnit4::class)
class LocationButtonsLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setButtons(width: Dp, fontScale: Float, location: LatLng? = null) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = fontScale),
            ) {
                Box(modifier = Modifier.width(width)) {
                    LocationButtons(
                        location = location,
                        locating = false,
                        onOpenPicker = {},
                        onUseMyLocation = {},
                    )
                }
            }
        }
    }

    /** Half a line above the one-line labelLarge height (lineHeight 20sp). */
    private fun oneLineThreshold(fontScale: Float): Dp = 20.dp * fontScale * 1.5f

    private fun assertOneLine(bounds: DpRect, label: String, fontScale: Float) {
        assertTrue(
            "'$label' broke across lines (label ${bounds.height} tall at ${fontScale}x)",
            bounds.height < oneLineThreshold(fontScale),
        )
    }

    private fun assertSameRow(open: DpRect, gps: DpRect) {
        assertTrue(
            "expected the two buttons on one row (tops ${open.top} and ${gps.top})",
            abs((open.top - gps.top).value) < 1f,
        )
    }

    @Test
    fun `add location and use my location fit on one line and share the row at 360dp default scale`() {
        setButtons(width = 360.dp, fontScale = 1f)

        val add = composeRule.onNodeWithText("Add location").getUnclippedBoundsInRoot()
        val gps = composeRule.onNodeWithText("Use my location").getUnclippedBoundsInRoot()

        assertOneLine(add, "Add location", 1f)
        assertOneLine(gps, "Use my location", 1f)
        assertSameRow(add, gps)
        assertTrue("expected Add location left of Use my location", add.left < gps.left)
        // Natural widths, not equal halves: the longer label's button is
        // visibly wider than the shorter one's.
        assertTrue(
            "expected the buttons to keep their natural widths (${add.width} vs ${gps.width})",
            gps.width - add.width > 10.dp,
        )
    }

    @Test
    fun `change location fits on one line next to use my location at 360dp default scale`() {
        setButtons(width = 360.dp, fontScale = 1f, location = LatLng(41.9, 12.5))

        val change = composeRule.onNodeWithText("Change location").getUnclippedBoundsInRoot()
        val gps = composeRule.onNodeWithText("Use my location").getUnclippedBoundsInRoot()

        assertOneLine(change, "Change location", 1f)
        assertOneLine(gps, "Use my location", 1f)
        assertSameRow(change, gps)
    }

    @Test
    fun `whole buttons wrap to a second line at a 2x font scale and no label breaks mid-word`() {
        setButtons(width = 360.dp, fontScale = 2f)

        val add = composeRule.onNodeWithText("Add location").getUnclippedBoundsInRoot()
        val gps = composeRule.onNodeWithText("Use my location").getUnclippedBoundsInRoot()

        // Never a mid-word break: every label is still exactly one line,
        // however far the buttons wrap.
        assertOneLine(add, "Add location", 2f)
        assertOneLine(gps, "Use my location", 2f)
        // The natural widths no longer fit the 360dp row at 2x, so one
        // whole button sits on a wrapped second line (its top clears the
        // first line's button by a full row, not a hair).
        val lineSpan = maxOf(add.top, gps.top) - minOf(add.top, gps.top)
        assertTrue(
            "expected a whole button to wrap to a second line, line span was $lineSpan",
            lineSpan > 16.dp,
        )
    }

    @Test
    fun `at an extreme font scale a label that cannot fit one line wraps to two whole lines`() {
        setButtons(width = 360.dp, fontScale = 3.5f)

        // 'Use my location' alone no longer fits the 360dp row at 3.5x, so
        // the FlowRow squeezes that one button to the row width and its
        // label takes two lines — the case the TextAlign.Center safety net
        // centers. 'Add location' still fits, so it stays on one line.
        val add = composeRule.onNodeWithText("Add location").getUnclippedBoundsInRoot()
        val gps = composeRule.onNodeWithText("Use my location").getUnclippedBoundsInRoot()

        assertOneLine(add, "Add location", 3.5f)
        assertTrue(
            "'Use my location' stayed on one line (label ${gps.height} tall at 3.5x)",
            gps.height > oneLineThreshold(3.5f),
        )
    }
}
