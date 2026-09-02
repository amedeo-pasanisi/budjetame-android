package com.budjetame.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * One slice of the Dashboard's category pies: a Category's Expenses or
 * Incomes in the reference month. `category_id` is null for the
 * "Uncategorized" slice (Transactions whose Category was deleted) — then
 * `color` is null too, and the screen renders a neutral color. The slices
 * always sum to the month's total for the pie's side.
 */
@Serializable
data class CategorySliceDto(
    val category_id: Int? = null,
    val name: String,
    val icon: String? = null,
    val color: String? = null,
    val amount: String,
)

/**
 * The Dashboard overview (web issue #17): Net Worth — the algebraic sum of
 * all Wallet balances, Contact and frozen Wallets included — and the
 * reference month's Income and Expense totals and its category pies, both
 * (the screen toggles between them). The month defaults to the current
 * Europe/Rome one; `?month=YYYY-MM` selects another and the whole summary
 * reflects it. Opening Balance Transactions never count toward the
 * statistics; Transfers are excluded by construction.
 */
@Serializable
data class DashboardSummaryDto(
    val net_worth: String,
    val month: String,
    val income: String,
    val expenses: String,
    val expenses_by_category: List<CategorySliceDto>,
    val incomes_by_category: List<CategorySliceDto>,
)

/**
 * Dashboard resource (web issue #17): the overview for one reference month.
 */
interface DashboardApi {

    /** 422 on a malformed `month` (the client only ever sends "YYYY-MM"). */
    @GET("dashboard/summary")
    suspend fun summary(@Query("month") month: String): DashboardSummaryDto
}
