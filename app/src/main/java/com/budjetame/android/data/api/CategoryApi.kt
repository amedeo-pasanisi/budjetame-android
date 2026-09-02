package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Category types (CONTEXT.md); the wire values match the backend's enum. */
@Serializable
enum class CategoryType {
    @SerialName("expense") EXPENSE,
    @SerialName("income") INCOME,
}

/** A Category as seen through the API. */
@Serializable
data class CategoryDto(
    val id: Int,
    val name: String,
    val type: CategoryType,
    val icon: String? = null,
    val color: String,
    val created_at: String,
)

/** Create a Category. The icon is always sent as a string ("" = no icon),
 * exactly like the web app; the backend stores "" as null. */
@Serializable
data class CategoryCreateRequest(
    val name: String,
    val type: CategoryType,
    val icon: String,
    val color: String,
)

/**
 * Edit a Category: name, icon, or color — the type cannot change. All three
 * fields are always sent, like the web app; "" clears the icon. A `name`
 * that collides with an existing same-Type Category is not applied: the
 * endpoint answers 409 with a structured detail carrying the existing
 * Category's id (`target_id`) and the count of Transactions on the renamed
 * Category (`transaction_count`) — the merge offer (ADR-0007 in the web
 * repo).
 */
@Serializable
data class CategoryUpdateRequest(
    val name: String,
    val icon: String,
    val color: String,
)

/** The confirmed merge: `target_id` is the id the 409 conflict carried. */
@Serializable
data class CategoryMergeRequest(
    val target_id: Int,
)

/**
 * Categories resource (web issue #17): list/create/update/merge/delete.
 */
interface CategoryApi {

    @GET("categories")
    suspend fun list(): List<CategoryDto>

    /** 201 with the created Category; 409 duplicate name; 422 invalid input. */
    @POST("categories")
    suspend fun create(@Body body: CategoryCreateRequest): CategoryDto

    /** 409 with the structured merge offer on a colliding rename. */
    @PATCH("categories/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: CategoryUpdateRequest): CategoryDto

    /**
     * The confirmed merge (ADR-0007): the renamed Category's Transactions
     * move to the target, the renamed Category is deleted, and the target
     * survives — one atomic write.
     */
    @POST("categories/{id}/merge")
    suspend fun merge(@Path("id") id: Int, @Body body: CategoryMergeRequest): CategoryDto

    /** Delete the Category; its Transactions become uncategorized (never deleted). */
    @DELETE("categories/{id}")
    suspend fun delete(@Path("id") id: Int)
}
