package com.budjetame.android.ui.categories

import androidx.annotation.StringRes
import com.budjetame.android.R
import com.budjetame.android.data.api.CategoryDto
import com.budjetame.android.data.api.CategoryType

/**
 * Presentation-only grouping for the Categories screen, ported from the web
 * app's CategoriesScreen (issue #41): two fixed sections — Expenses and
 * Incomes — each sorted A→Z case-insensitively, filtered by the live search
 * needle.
 */

private val SECTION_TYPES: List<CategoryType> = listOf(
    CategoryType.EXPENSE,
    CategoryType.INCOME,
)

/** Singular type label (mirrors the web app), as a string resource. */
@StringRes
fun categoryTypeLabel(type: CategoryType): Int = when (type) {
    CategoryType.EXPENSE -> R.string.categories_type_expense
    CategoryType.INCOME -> R.string.categories_type_income
}

/** Plural section header (mirrors the web app), as a string resource. */
@StringRes
fun categorySectionLabel(type: CategoryType): Int = when (type) {
    CategoryType.EXPENSE -> R.string.categories_section_expenses
    CategoryType.INCOME -> R.string.categories_section_incomes
}

data class CategorySection(
    val type: CategoryType,
    @StringRes val label: Int,
    val items: List<CategoryDto>,
)

/**
 * Fixed-order sections filtered by the search needle (case-insensitive name
 * substring, live) and sorted A→Z case-insensitively, so a new Category
 * lands at the sorted position of its section automatically.
 */
fun categorySections(categories: List<CategoryDto>, query: String): List<CategorySection> {
    val needle = query.trim().lowercase()
    val filtered = if (needle.isEmpty()) {
        categories
    } else {
        categories.filter { it.name.lowercase().contains(needle) }
    }
    return SECTION_TYPES.map { type ->
        CategorySection(
            type = type,
            label = categorySectionLabel(type),
            items = filtered
                .filter { it.type == type }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
        )
    }
}
