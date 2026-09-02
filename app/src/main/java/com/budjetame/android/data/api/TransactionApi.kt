package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
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
}
