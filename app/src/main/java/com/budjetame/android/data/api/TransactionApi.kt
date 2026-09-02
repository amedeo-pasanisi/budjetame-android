package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Transaction types (CONTEXT.md); the wire values match the backend's enum. */
@Serializable
enum class TransactionType {
    @SerialName("expense") EXPENSE,
    @SerialName("income") INCOME,
    @SerialName("opening_balance") OPENING_BALANCE,
    @SerialName("transfer") TRANSFER,
}

/**
 * A Transaction as seen through the API. Expense/Income/Opening Balance fill
 * `wallet_id`; a Transfer fills `source_wallet_id` and
 * `destination_wallet_id` and never carries a Category. Amounts travel as
 * strings ("12.50"); `date` is the calendar day in Europe/Rome (CONTEXT.md).
 */
@Serializable
data class TransactionDto(
    val id: Int,
    val type: TransactionType,
    val amount: String,
    val date: String,
    val wallet_id: Int? = null,
    val source_wallet_id: Int? = null,
    val destination_wallet_id: Int? = null,
    val category_id: Int? = null,
    val recurring_cost_id: Int? = null,
    val recurring_income_id: Int? = null,
    val occurrence_date: String? = null,
    val description: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val place_name: String? = null,
    val place_id: String? = null,
    val warning: Boolean = false,
    val created_at: String,
)

/**
 * One page of the Transactions list (cursor paging, web issue #30): the
 * page's rows, newest first, and the opaque `next_cursor` for the next page
 * — null exactly when this is the last page. Clients hand `next_cursor`
 * back verbatim; they never parse it.
 */
@Serializable
data class TransactionPageDto(
    val items: List<TransactionDto>,
    val next_cursor: String? = null,
)

/** Page size for the ledger, matching the backend default (web issue #30). */
const val TRANSACTION_PAGE_LIMIT = 50

/**
 * Record an Expense, Income, or Transfer (never an Opening Balance — those
 * are created by the Wallet lifecycle). Expense/Income fill `wallet_id`
 * (plus an optional matching `category_id`); a Transfer fills
 * `source_wallet_id` and `destination_wallet_id` and never carries a
 * Category. `recurring_cost_id` is the optional Recurring Cost link (web
 * issue #57): Expenses only — the form never offers it to an Income or a
 * Transfer — and the link pays the cost's oldest Unpaid Occurrence at link
 * time. `recurring_income_id` is the optional Recurring Income link (web
 * issue #61), the mirror: Incomes only, paying the income's oldest Unpaid
 * Occurrence at link time. A Transaction is one type, so at most one of the
 * two keys is ever set. Amounts travel as strings; `date` is the calendar
 * day in Europe/Rome (CONTEXT.md). Null fields are omitted from the wire
 * body (the converter skips default-valued fields), so a Transfer never
 * sends `wallet_id`/`category_id`/the link keys, an unlinked Expense never
 * sends `recurring_cost_id`, an unlinked Income never sends
 * `recurring_income_id`, and an Expense never sends the Transfer legs.
 */
@Serializable
data class TransactionCreateRequest(
    val type: TransactionType,
    val amount: String,
    val date: String,
    val wallet_id: Int? = null,
    val source_wallet_id: Int? = null,
    val destination_wallet_id: Int? = null,
    val category_id: Int? = null,
    val recurring_cost_id: Int? = null,
    val recurring_income_id: Int? = null,
    val description: String? = null,
)

/**
 * Edit an Expense or Income whose recurring link is untouched (or that
 * carries none): amount, date, and description are always sent (`null`
 * description clears it); `category_id` is always sent too — a field present
 * in the PATCH is applied even when null, which is how clearing the Category
 * works. The absent `recurring_cost_id`/`recurring_income_id` keys mean the
 * stored links stay pinned: a mere amount, date, or Category edit never
 * reassigns the Occurrence a link pays. An Expense's PATCH always takes this
 * shape when its cost pick is unchanged, an Income's when its income pick is
 * unchanged — the backend rejects a mismatched link key (a cost key on an
 * Income, an income key on an Expense), so the key a type never carries
 * must not exist on the wire.
 */
@Serializable
data class TransactionExpenseIncomeUpdateRequest(
    val amount: String,
    val date: String,
    val category_id: Int?,
    val description: String?,
)

/**
 * Edit an Expense whose Recurring Cost link the form changed (web issue
 * #57): every field is sent — `recurring_cost_id` present with a value
 * links (or relinks), paying the cost's oldest Unpaid Occurrence at that
 * moment; present as null it unlinks, freeing the Occurrence. The key is
 * always on the wire here, null included (the converter's `explicitNulls`) —
 * that is what tells the backend to apply the change instead of leaving the
 * stored pin untouched. `recurring_income_id` never exists on this shape:
 * the backend rejects it on anything but an Income.
 */
@Serializable
data class TransactionExpenseLinkUpdateRequest(
    val amount: String,
    val date: String,
    val category_id: Int?,
    val description: String?,
    val recurring_cost_id: Int?,
)

/**
 * Edit an Income whose Recurring Income link the form changed (web issue
 * #61), the mirror of the Expense-link shape: `recurring_income_id` present
 * with a value links (or relinks), paying the income's oldest Unpaid
 * Occurrence at that moment; present as null it unlinks, freeing the
 * Occurrence. The key is always on the wire here, null included (the
 * converter's `explicitNulls`) — that is what tells the backend to apply
 * the change instead of leaving the stored pin untouched.
 * `recurring_cost_id` never exists on this shape: the backend rejects it on
 * anything but an Expense.
 */
@Serializable
data class TransactionIncomeLinkUpdateRequest(
    val amount: String,
    val date: String,
    val category_id: Int?,
    val description: String?,
    val recurring_income_id: Int?,
)

/**
 * Edit a Transfer: amount, date, and description are always sent;
 * `category_id` is absent — the backend rejects a `category_id` key on a
 * Transfer even when it is null, so the field must not exist on the wire.
 */
@Serializable
data class TransactionTransferUpdateRequest(
    val amount: String,
    val date: String,
    val description: String?,
)

/** The result of a Transaction delete (US10/ID8): the Cash negative-balance
 * indicator — true exactly when the delete left a Cash Wallet negative. */
@Serializable
data class TransactionDeleteResultDto(
    val warning: Boolean,
)

/**
 * Transactions resource (web issue #17): the ledger listing with cursor
 * paging and the shared filter set — a Wallet (frozen ones included,
 * matching a Transfer on either leg), a Category, an inclusive Europe/Rome
 * date range, the Description needle (case-insensitive, accent-exact,
 * literal), and the Recurring link (web issue #86): a specific definition
 * id narrows to the Transactions linked to exactly that Recurring Cost or
 * Recurring Income. At most one of the two recurring keys is ever sent —
 * the filter bar's Recurring select is a single pick, and a Transaction is
 * one type, so the two can never both apply; a missing or foreign
 * definition id answers 403 like the other filters. Absent query params
 * are omitted by Retrofit.
 */
interface TransactionApi {

    /**
     * The whole filtered ledger as the import template's .xlsx (US 7.3,
     * ticket #28): the export carries exactly the listing's filter set —
     * never the paging keys — and answers the raw workbook, not JSON, so
     * Retrofit returns the unparsed response: the file's bytes come from
     * the body, the dated filename from Content-Disposition. Absent query
     * params are omitted by Retrofit, like the listing's.
     */
    @GET("transactions/export")
    suspend fun export(
        @Query("wallet_id") walletId: Int? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
        @Query("recurring_cost_id") recurringCostId: Int? = null,
        @Query("recurring_income_id") recurringIncomeId: Int? = null,
        @Query("q") q: String? = null,
    ): Response<ResponseBody>

    @GET("transactions")
    suspend fun list(
        @Query("wallet_id") walletId: Int? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
        @Query("recurring_cost_id") recurringCostId: Int? = null,
        @Query("recurring_income_id") recurringIncomeId: Int? = null,
        @Query("q") q: String? = null,
        @Query("limit") limit: Int = TRANSACTION_PAGE_LIMIT,
        @Query("cursor") cursor: String? = null,
    ): TransactionPageDto

    /** 201 with the created Transaction; 422 on a type-rule violation. */
    @POST("transactions")
    suspend fun create(@Body body: TransactionCreateRequest): TransactionDto

    /** PATCH for an Expense/Income whose Recurring Cost link is untouched:
     * `category_id` is always present, `recurring_cost_id` never is. */
    @PATCH("transactions/{id}")
    suspend fun updateExpenseIncome(
        @Path("id") id: Int,
        @Body body: TransactionExpenseIncomeUpdateRequest,
    ): TransactionDto

    /** PATCH for an Expense whose Recurring Cost link the form changed:
     * `recurring_cost_id` is always present — a value links, null unlinks. */
    @PATCH("transactions/{id}")
    suspend fun updateExpenseLink(
        @Path("id") id: Int,
        @Body body: TransactionExpenseLinkUpdateRequest,
    ): TransactionDto

    /** PATCH for an Income whose Recurring Income link the form changed:
     * `recurring_income_id` is always present — a value links, null unlinks. */
    @PATCH("transactions/{id}")
    suspend fun updateIncomeLink(
        @Path("id") id: Int,
        @Body body: TransactionIncomeLinkUpdateRequest,
    ): TransactionDto

    /** PATCH for a Transfer: no `category_id` key on the wire. */
    @PATCH("transactions/{id}")
    suspend fun updateTransfer(
        @Path("id") id: Int,
        @Body body: TransactionTransferUpdateRequest,
    ): TransactionDto

    /** 200 with the Cash negative-balance indicator; 422 on a frozen Wallet
     * or an Opening Balance (both are read-only). */
    @DELETE("transactions/{id}")
    suspend fun delete(@Path("id") id: Int): TransactionDeleteResultDto
}
