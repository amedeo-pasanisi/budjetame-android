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
 * The trend chart's two sides, named like the web app's `TrendKind` and the
 * endpoint paths (`expense-trend` / `income-trend`). Both pies of the
 * summary ride one response; each trend side is its own endpoint.
 */
enum class TrendKind {
    EXPENSE,
    INCOME,
}

/**
 * One bucket of a Dashboard trend (web issue #29, T12): a Europe/Rome month
 * and the total Expenses (`kind` expense) or Incomes (`kind` income)
 * recorded in it, zero-filled when nothing was recorded.
 */
@Serializable
data class MonthBucketDto(
    val month: String,
    val amount: String,
)

/**
 * A monthly trend over an inclusive month range (T12, US28): one bucket per
 * month, oldest first, zero-filled for months with nothing recorded,
 * bucketed in Europe/Rome server-side. `expense-trend` serves Expenses and
 * `income-trend` Incomes — same shape, the screen toggles between them.
 */
@Serializable
data class TrendDto(
    val from_month: String,
    val to_month: String,
    val months: List<MonthBucketDto>,
)

/**
 * The Dashboard's Budget card frame (web issue #65): the current
 * Europe/Rome month — deliberately no month parameter, the Budget is
 * current-month-only by product decision. Everything is derived server-side
 * per ADR-0012 (`monthly_spendable` counts Recurring Occurrences due in the
 * month; `daily_allowance` divides it by the days of the month, floored to
 * the cent, floored at 0 when the month is negative; `spendable_today` is
 * the allowance accrued through today minus the Discretionary Expenses
 * dated in that span). `spendable_today` is sent raw and possibly negative:
 * the card renders it as 0 until future accruals repay it.
 */
@Serializable
data class BudgetDto(
    val month: String,
    val monthly_spendable: String,
    val daily_allowance: String,
    val spendable_today: String,
)

/**
 * Dashboard resource (web issue #17): the overview for one reference month.
 */
interface DashboardApi {

    /** 422 on a malformed `month` (the client only ever sends "YYYY-MM"). */
    @GET("dashboard/summary")
    suspend fun summary(@Query("month") month: String): DashboardSummaryDto

    /** Monthly Expense totals over the inclusive range (T12, US28): one
     * zero-filled bucket per Europe/Rome month, oldest first. 422 on a
     * malformed month or a reversed range — the screen's swap rule never
     * sends one. */
    @GET("dashboard/expense-trend")
    suspend fun expenseTrend(
        @Query("from_month") fromMonth: String,
        @Query("to_month") toMonth: String,
    ): TrendDto

    /** The Income mirror of [expenseTrend] — same shape and rules, so the
     * trend toggle reuses one chart. */
    @GET("dashboard/income-trend")
    suspend fun incomeTrend(
        @Query("from_month") fromMonth: String,
        @Query("to_month") toMonth: String,
    ): TrendDto

    /** The Budget card's frame (web issue #65): current-month-only — the
     * client sends no month and never computes the frame. */
    @GET("dashboard/budget")
    suspend fun budget(): BudgetDto
}
