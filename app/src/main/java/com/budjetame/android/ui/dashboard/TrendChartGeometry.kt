package com.budjetame.android.ui.dashboard

/**
 * The monthly trend plot's geometry in pixels — one shared description of
 * where bars, gridlines and columns sit, so drawing, month labels, and the
 * tap/press hit tests can never drift apart (the press-tooltip ticket #42
 * and the full-width ticket #40 land on the same geometry).
 *
 * The rule (ticket #40): the plot always fills the card's inner width —
 * the content width is max(fixed geometry, available card width). While
 * the fixed geometry ([fixedWidth]) fits, the bars keep the original
 * layout exactly: a [barGap] leading slot, then the bars with [barGap]
 * between them, the last bar flush with the plot's end. Once the canvas is
 * wider, the leftover width splits evenly across [count] + 1 slots —
 * leading, between the bars, and trailing all equal — so the bars spread
 * evenly across the full plot with their widths unchanged, the gaps grown
 * symmetrically, and the gridlines spanning the whole content width.
 */
internal class TrendChartGeometry(
    /** Number of bars (months). */
    val count: Int,
    /** Bar width, px. */
    val barWidth: Float,
    /** The fixed layout's gap between bars, px. */
    val barGap: Float,
    /** The left pad, px, reserved for the Y axis' € labels. */
    val leftPad: Float,
    /** The content (canvas) width, px, the plot must fill. */
    val contentWidth: Float,
) {
    init {
        require(count >= 1) { "a trend chart needs at least one bar" }
        require(barWidth > 0f) { "barWidth must be positive" }
        require(barGap >= 0f) { "barGap must be non-negative" }
        require(leftPad >= 0f) { "leftPad must be non-negative" }
        require(contentWidth >= 0f) { "contentWidth must be non-negative" }
    }

    /** The fixed geometry's content width: the left pad plus one full
     * bar+gap slot per bar. */
    val fixedWidth: Float get() = leftPad + count * (barWidth + barGap)

    /** True when the canvas is wider than the fixed geometry, so the slots
     * grow and the bars spread to fill it. */
    val stretched: Boolean get() = contentWidth > fixedWidth

    /** The slot between two bar starts minus the bar width: [barGap] while
     * the fixed geometry fits; once stretched, the whole leftover
     * (contentWidth − leftPad − count·barWidth) splits evenly across the
     * count + 1 slots, so the leading and trailing insets equal the gaps
     * between the bars. */
    val gap: Float
        get() = if (contentWidth <= fixedWidth) barGap
        else (contentWidth - leftPad - count * barWidth) / (count + 1)

    /** One column's pitch: the bar width plus the following slot. */
    val barStep: Float get() = barWidth + gap

    /** The left edge of bar [index]. */
    fun barLeft(index: Int): Float = leftPad + gap + index * barStep

    /** The horizontal center of bar [index]. */
    fun barCenter(index: Int): Float = barLeft(index) + barWidth / 2f

    /**
     * The bar whose column contains the x offset — a tap/press target is
     * the whole column (from the bar's left edge to the next bar's left
     * edge, the last one to the content width), like the web app's
     * transparent column rects. Null outside every column: the axis/label
     * pad and the leading slot before the first bar.
     */
    fun columnIndexAt(x: Float): Int? {
        if (x < barLeft(0)) return null
        val index = ((x - barLeft(0)) / barStep).toInt()
        return if (index in 0 until count) index else null
    }
}

/**
 * The pressed bar's value chip top edge (ticket #42): the chip floats
 * [chipGapPx] above the bar's top — and never above the chart's top edge,
 * so a near-full-height bar's chip stays inside the chart. In content
 * pixels, whose y = 0 is the chart's top edge.
 */
internal fun chipTopForBar(topOfBarPx: Float, chipHeightPx: Float, chipGapPx: Float): Float =
    (topOfBarPx - chipGapPx - chipHeightPx).coerceAtLeast(0f)

/**
 * The pressed bar's value chip left edge (ticket #42): centered on the
 * bar and kept inside the chart's sides — a chip wider than the first
 * bar's inset never starts left of the chart, and over the last
 * fixed-layout bar (flush with the content's end) it never runs past the
 * content's right edge.
 */
internal fun chipLeftForBar(barCenterPx: Float, chipWidthPx: Float, contentWidthPx: Float): Float =
    (barCenterPx - chipWidthPx / 2f)
        .coerceIn(0f, (contentWidthPx - chipWidthPx).coerceAtLeast(0f))
