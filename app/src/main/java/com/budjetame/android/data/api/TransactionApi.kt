package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 * Category. Amounts travel as strings; `date` is the calendar day in
 * Europe/Rome (CONTEXT.md). Null fields are omitted from the wire body (the
 * converter skips default-valued fields), so a Transfer never sends
 * `wallet_id`/`category_id` and an Expense never sends the Transfer legs.
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
    val description: String? = null,
)

/**
 * Edit an Expense or Income: amount, date, and description are always sent
 * (`null` description clears it); `category_id` is always sent too — a field
 * present in the PATCH is applied even when null, which is how clearing the
 * Category works. Type and Wallets cannot change.
 */
@Serializable
data class TransactionExpenseIncomeUpdateRequest(
    val amount: String,
    val date: String,
    val category_id: Int?,
    val description: String?,
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
 * date range, and the Description needle (case-insensitive, accent-exact,
 * literal). Absent query params are omitted by Retrofit.
 */
interface TransactionApi {

    @GET("transactions")
    suspend fun list(
        @Query("wallet_id") walletId: Int? = null,
        @Query("category_id") categoryId: Int? = null,
        @Query("from_date") fromDate: String? = null,
        @Query("to_date") toDate: String? = null,
        @Query("q") q: String? = null,
        @Query("limit") limit: Int = TRANSACTION_PAGE_LIMIT,
        @Query("cursor") cursor: String? = null,
    ): TransactionPageDto

    /** 201 with the created Transaction; 422 on a type-rule violation. */
    @POST("transactions")
    suspend fun create(@Body body: TransactionCreateRequest): TransactionDto

    /** PATCH for an Expense/Income: `category_id` is always present. */
    @PATCH("transactions/{id}")
    suspend fun updateExpenseIncome(
        @Path("id") id: Int,
        @Body body: TransactionExpenseIncomeUpdateRequest,
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
