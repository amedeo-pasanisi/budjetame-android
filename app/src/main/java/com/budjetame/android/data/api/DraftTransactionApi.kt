package com.budjetame.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * A Draft Transaction as seen through the API (CONTEXT.md; ADR-0036 in the
 * web repo): the stored, unapproved record the Assistant produces — a
 * Transaction's fields, any of them still missing (null) — scoped to the
 * Account and counting toward nothing until Approved. `type` is never an
 * Opening Balance; `date` is the Europe/Rome calendar day.
 */
@Serializable
data class DraftTransactionDto(
    val id: Int,
    val type: TransactionType? = null,
    val amount: String? = null,
    val date: String? = null,
    val wallet_id: Int? = null,
    val source_wallet_id: Int? = null,
    val destination_wallet_id: Int? = null,
    val category_id: Int? = null,
    val recurring_cost_id: Int? = null,
    val recurring_income_id: Int? = null,
    val description: String? = null,
    val created_at: String,
)

/**
 * Draft Transactions resource (web issues #122/#123/#125). Every write goes
 * through the transport, so it bumps the data version (ADR-0002).
 */
interface DraftTransactionApi {

    /** The Account's Drafts, oldest first; never paginated. */
    @GET("draft-transactions")
    suspend fun list(): List<DraftTransactionDto>

    /** Approve the Draft as it stands — the one tap. 201 with the created
     * Transaction (write warning included); 422 with the broken rule as
     * `detail`, the Draft left in place; 403 when it is gone. */
    @POST("draft-transactions/{id}/approve")
    suspend fun approve(@Path("id") id: Int): TransactionDto

    /** Approve with the form's edited fields — the full create body, used in
     * place of the Draft's. Same answers as the one tap. */
    @POST("draft-transactions/{id}/approve")
    suspend fun approveEdited(@Path("id") id: Int, @Body body: TransactionCreateRequest): TransactionDto

    /** Discard: 204; nothing is created, and there is no Undo. */
    @DELETE("draft-transactions/{id}")
    suspend fun discard(@Path("id") id: Int)
}
