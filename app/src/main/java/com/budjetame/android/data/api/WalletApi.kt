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

/** Wallet types (CONTEXT.md); the wire values match the backend's enum. */
@Serializable
enum class WalletType {
    @SerialName("checking") CHECKING,
    @SerialName("credit_card") CREDIT_CARD,
    @SerialName("cash") CASH,
    @SerialName("contact") CONTACT,
}

/** A Wallet as seen through the API, with its derived balance (ADR-0001). */
@Serializable
data class WalletDto(
    val id: Int,
    val name: String,
    val type: WalletType,
    val balance: String,
    val frozen: Boolean,
    val created_at: String,
)

@Serializable
data class WalletCreateRequest(
    val name: String,
    val type: WalletType,
    val opening_balance: String,
)

@Serializable
data class WalletUpdateRequest(
    val name: String,
)

/**
 * Wallets resource (web issue #17): list/create/rename/freeze/unfreeze.
 */
interface WalletApi {

    /** `include_frozen=true` so the collapsed Frozen Wallets list can render. */
    @GET("wallets")
    suspend fun list(@Query("include_frozen") includeFrozen: Boolean): List<WalletDto>

    /** 201 with the created Wallet; 409 duplicate name; 422 invalid input. */
    @POST("wallets")
    suspend fun create(@Body body: WalletCreateRequest): WalletDto

    /** Rename (type immutable). 409 duplicate name; 422 frozen or invalid. */
    @PATCH("wallets/{id}")
    suspend fun rename(@Path("id") id: Int, @Body body: WalletUpdateRequest): WalletDto

    /** Freeze a Wallet at balance exactly €0; 422 otherwise. */
    @DELETE("wallets/{id}")
    suspend fun freeze(@Path("id") id: Int)

    /** Unfreeze a Frozen Wallet, restoring it to active. */
    @POST("wallets/{id}/unfreeze")
    suspend fun unfreeze(@Path("id") id: Int): WalletDto
}
