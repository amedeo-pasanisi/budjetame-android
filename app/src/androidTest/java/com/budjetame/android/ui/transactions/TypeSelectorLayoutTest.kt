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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.TransactionType
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The type picker's width behavior (ticket #36): each button keeps its
 * natural width inside a FlowRow, so a label can never be squeezed below
 * one line — when the three do not fit the 360dp dialog width at an
 * extreme font scale, whole buttons wrap onto further lines. The selector
 * is driven directly at a fixed width and font scale (the same harness
 * the header-fit test in TransactionsChromeTest uses), since the dialog
 * chrome cannot be resized or re-scaled from a test: the assertions hold
 * for the shared TypeSelector wherever it renders — the Transaction modal
 * and the Import row editor alike. Label heights are compared against the
 * M3 labelLarge line box (20sp per line at the forced scale): one line
 * measures 20sp × scale, a wrapped label at least twice that, so the
 * mid-word threshold sits between the two and never depends on glyph
 * metrics.
 */
@RunWith(AndroidJUnit4::class)
class TypeSelectorLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setSelector(width: Dp, fontScale: Float, selected: TransactionType) {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = fontScale),
            ) {
                Box(modifier = Modifier.width(width)) {
                    TypeSelector(selected = selected, enabled = true, onSelect = {})
                }
            }
        }
    }

    /** Half a line above the one-line labelLarge height (lineHeight 20sp). */
    private fun oneLineThreshold(fontScale: Float): Dp = 20.dp * fontScale * 1.5f

    @Test
    fun `each type label stays on one line at 360dp up to a 1_3 font scale`() {
        setSelector(width = 360.dp, fontScale = 1.3f, selected = TransactionType.EXPENSE)

        listOf("Expense", "Income", "Transfer").forEach { label ->
            val height = composeRule.onNodeWithText(label).getUnclippedBoundsInRoot().height
            assertTrue(
                "'$label' broke across lines (label ${height} tall at 1.3x)",
                height < oneLineThreshold(1.3f),
            )
        }
    }

    @Test
    fun `whole buttons wrap to a second line at an extreme font scale and no label breaks mid-word`() {
        setSelector(width = 360.dp, fontScale = 2f, selected = TransactionType.EXPENSE)

        val bounds = listOf("Expense", "Income", "Transfer").map { label ->
            composeRule.onNodeWithText(label).getUnclippedBoundsInRoot()
        }
        // Never a mid-word break: every label is still exactly one line,
        // however far the buttons wrap.
        listOf("Expense", "Income", "Transfer").forEachIndexed { index, label ->
            assertTrue(
                "'$label' broke across lines (label ${bounds[index].height} tall at 2x)",
                bounds[index].height < oneLineThreshold(2f),
            )
        }
        // The natural widths no longer fit the 360dp dialog at 2x, so at
        // least one whole button sits on a wrapped second line (its top
        // clears the first line's buttons by a full row, not a hair).
        val lineSpan = bounds.maxOf { it.top } - bounds.minOf { it.top }
        assertTrue(
            "expected a whole button to wrap to a second line, line span was $lineSpan",
            lineSpan > 16.dp,
        )
    }
}
