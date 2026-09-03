package com.budjetame.android.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trend plot's pure geometry (ticket #40): while the fixed geometry
 * fits the card, the bars keep the original layout — a barGap leading
 * slot, the last bar flush with the plot's end; once the canvas is wider,
 * the leftover width splits evenly across count + 1 slots, so the bars
 * spread across the full plot with their widths unchanged, symmetric gaps,
 * and gridlines spanning the whole content width. The tap columns always
 * track the bars, in both layouts.
 */
class TrendChartGeometryTest {

    private val barWidth = 22f
    private val barGap = 12f
    private val leftPad = 30f

    private fun geometry(count: Int, contentWidth: Float) = TrendChartGeometry(
        count = count,
        barWidth = barWidth,
        barGap = barGap,
        leftPad = leftPad,
        contentWidth = contentWidth,
    )

    /** fixedWidth for count bars at the chart's constants. */
    private fun fixedWidth(count: Int) = leftPad + count * (barWidth + barGap)

    // Fixed layout — a wide (12-month) range never stretches.

    @Test
    fun wideRangeKeepsTheFixedGeometry() {
        val count = 12
        val g = geometry(count, contentWidth = fixedWidth(count)) // 438

        assertFalse(g.stretched)
        assertEquals(barGap, g.gap, 1e-4f)
        // Bars sit exactly where they always did: barGap leading slot,
        // barGap between bars, the last bar flush with the plot's end.
        assertEquals(leftPad + barGap, g.barLeft(0), 1e-4f)
        assertEquals(barWidth + barGap, g.barStep, 1e-4f)
        val lastBarRight = g.barLeft(count - 1) + barWidth
        assertEquals(g.contentWidth, lastBarRight, 1e-4f)
        // The canvas is the fixed width: the plot ends at the last bar.
        assertEquals(fixedWidth(count), g.contentWidth, 1e-4f)
    }

    @Test
    fun fixedGeometrySurvivesAScrollingCanvasWiderThanTheCard() {
        // The 12-month content (438) inside a card that is only 300 wide:
        // content width stays the fixed geometry — unchanged, scrollable.
        val g = geometry(count = 12, contentWidth = 438f)

        assertFalse(g.stretched)
        assertEquals(fixedWidth(12), g.contentWidth, 1e-4f)
        assertEquals(barGap, g.gap, 1e-4f)
        assertEquals(leftPad + barGap, g.barLeft(0), 1e-4f)
    }

    // Stretched layout — short ranges fill the card.

    @Test
    fun threeBarsSpreadEvenlyAcrossTheWiderCanvas() {
        val g = geometry(count = 3, contentWidth = 300f)

        assertTrue(g.stretched)
        // 300 − leftPad(30) − 3·22 leaves 204 over 4 symmetric slots.
        assertEquals(51f, g.gap, 1e-4f)
        assertEquals(81f, g.barLeft(0), 1e-4f)
        assertEquals(154f, g.barLeft(1), 1e-4f)
        assertEquals(227f, g.barLeft(2), 1e-4f)
        // Leading inset == trailing inset == the grown gap: symmetric.
        assertEquals(g.gap, g.barLeft(0) - leftPad, 1e-4f)
        assertEquals(g.gap, g.contentWidth - (g.barLeft(2) + barWidth), 1e-4f)
        // Bar widths never change.
        assertEquals(barWidth, g.barStep - g.gap, 1e-4f)
    }

    @Test
    fun singleBarCentersInTheWiderCanvas() {
        val g = geometry(count = 1, contentWidth = 300f)

        assertTrue(g.stretched)
        val barCenter = g.barCenter(0)
        assertEquals(leftPad + (g.contentWidth - leftPad) / 2f, barCenter, 1e-4f)
        assertEquals(g.gap, g.barLeft(0) - leftPad, 1e-4f)
        assertEquals(g.gap, g.contentWidth - (g.barLeft(0) + barWidth), 1e-4f)
    }

    @Test
    fun twoBarsSpreadWithSymmetricInserts() {
        val g = geometry(count = 2, contentWidth = 300f)

        assertTrue(g.stretched)
        // 300 − 30 − 44 = 226 over 3 slots.
        assertEquals(226f / 3f, g.gap, 1e-4f)
        // Bar widths never change.
        assertEquals(barWidth, g.barWidth, 1e-4f)
        assertEquals(g.gap, g.barLeft(0) - leftPad, 1e-4f)
        assertEquals(g.gap, g.contentWidth - (g.barLeft(1) + barWidth), 1e-4f)
    }

    // Tap columns track the bars in both layouts.

    @Test
    fun columnsFollowTheBarsInTheFixedLayout() {
        val g = geometry(count = 3, contentWidth = fixedWidth(3)) // 132

        // The axis/label pad and leading slot belong to no column.
        assertNull(g.columnIndexAt(0f))
        assertNull(g.columnIndexAt(leftPad + barGap - 0.1f))
        // Columns run from each bar's left edge to the next bar's.
        assertEquals(0, g.columnIndexAt(g.barLeft(0)))
        assertEquals(0, g.columnIndexAt(g.barLeft(1) - 0.1f))
        assertEquals(1, g.columnIndexAt(g.barLeft(1)))
        assertEquals(1, g.columnIndexAt(g.barLeft(2) - 0.1f))
        assertEquals(2, g.columnIndexAt(g.barLeft(2)))
        assertEquals(2, g.columnIndexAt(g.contentWidth - 0.1f))
    }

    @Test
    fun columnsFollowTheBarsInTheStretchedLayout() {
        val g = geometry(count = 3, contentWidth = 300f)

        assertNull(g.columnIndexAt(0f))
        assertNull(g.columnIndexAt(g.barLeft(0) - 0.1f))
        assertEquals(0, g.columnIndexAt(g.barLeft(0)))
        assertEquals(0, g.columnIndexAt(g.barLeft(1) - 0.1f))
        assertEquals(1, g.columnIndexAt(g.barLeft(1)))
        assertEquals(1, g.columnIndexAt(g.barLeft(2) - 0.1f))
        assertEquals(2, g.columnIndexAt(g.barLeft(2)))
        // The last column runs to the content's edge, exactly where the
        // stretched plot ends.
        assertEquals(2, g.columnIndexAt(g.contentWidth - 0.1f))
        assertNull(g.columnIndexAt(g.contentWidth))
    }

    @Test
    fun singleBarColumnCoversTheWholePlotInTheStretchedLayout() {
        val g = geometry(count = 1, contentWidth = 300f)

        assertNull(g.columnIndexAt(g.barLeft(0) - 0.1f))
        assertEquals(0, g.columnIndexAt(g.barLeft(0)))
        assertEquals(0, g.columnIndexAt(g.contentWidth - 0.1f))
    }

    // The pressed bar's value chip (ticket #42) — pure placement rules.

    @Test
    fun chipFloatsJustAboveTheBar() {
        // A baseline bar's top sits 134 px down the 150 px chart; the
        // 20 px chip keeps its 4 px gap below it.
        assertEquals(
            110f,
            chipTopForBar(topOfBarPx = 134f, chipHeightPx = 20f, chipGapPx = 4f),
            1e-4f,
        )
    }

    @Test
    fun chipNeverRisesAboveTheChartsTopEdge() {
        // A full-height bar's top sits at the plot's top (ChartTopPad, 20
        // at density 1): the chip would start above the chart, so it
        // clamps to the chart's top edge — and so does any bar closer to
        // the top than the chip plus its gap.
        assertEquals(0f, chipTopForBar(20f, 20f, 4f), 1e-4f)
        assertEquals(0f, chipTopForBar(23f, 20f, 4f), 1e-4f)
        // The threshold bar top (gap + chip height) exactly touches the
        // top edge; just below it the chip floats normally.
        assertEquals(0f, chipTopForBar(24f, 20f, 4f), 1e-4f)
        assertEquals(1f, chipTopForBar(25f, 20f, 4f), 1e-4f)
    }

    @Test
    fun chipCentersOnTheBar() {
        assertEquals(
            33f,
            chipLeftForBar(barCenterPx = 53f, chipWidthPx = 40f, contentWidthPx = 234f),
            1e-4f,
        )
    }

    @Test
    fun chipStaysInsideTheChartAtBothEdges() {
        // A chip wider than the first bar's inset never starts left of
        // the chart.
        assertEquals(
            0f,
            chipLeftForBar(barCenterPx = 53f, chipWidthPx = 120f, contentWidthPx = 234f),
            1e-4f,
        )
        // The last fixed-layout bar sits flush with the content's end
        // (center 234 − 11); a chip centered on it would run past the
        // right edge, so it is pushed back inside the content.
        assertEquals(
            188f,
            chipLeftForBar(barCenterPx = 223f, chipWidthPx = 46f, contentWidthPx = 234f),
            1e-4f,
        )
        // A mid-plot bar keeps the chip centered on it (never clamped).
        assertEquals(
            71f,
            chipLeftForBar(barCenterPx = 100f, chipWidthPx = 58f, contentWidthPx = 234f),
            1e-4f,
        )
    }
}
