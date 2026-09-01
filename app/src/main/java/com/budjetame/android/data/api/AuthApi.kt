package com.budjetame.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST

/** The single login identity (CONTEXT.md: Account). */
@Serializable
data class AccountDto(
    val id: Int,
    val email: String,
)

/** Public sign-in options: an empty client id means no Google button. */
@Serializable
data class AuthConfigDto(
    val google_client_id: String,
)

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String = "bearer",
)

@Serializable
data class CredentialsRequest(
    val email: String,
    val password: String,
)

@Serializable
data class GoogleTokenRequest(
    val id_token: String,
)

@Serializable
data class EmailRequest(
    val email: String,
)

@Serializable
data class ResetPasswordRequest(
    val token: String,
    val new_password: String,
)

/**
 * Auth resource: login, registration, Google sign-in, password reset, and
 * the current Account (web issues #17, #81, #82, #83, #84).
 */
interface AuthApi {

    @POST("auth/login")
    suspend fun login(@Body body: CredentialsRequest): TokenResponse

    /** Create an Account and sign it in (web ADR-0020): 409 = email taken. */
    @POST("auth/register")
    suspend fun register(@Body body: CredentialsRequest): TokenResponse

    @GET("auth/config")
    suspend fun config(): AuthConfigDto

    /** Sign in with a Google ID token: auto-provisions or links by email (web ADR-0021). */
    @POST("auth/google")
    suspend fun google(@Body body: GoogleTokenRequest): TokenResponse

    /** Always succeeds — the backend answers 204 for unknown emails too. */
    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: EmailRequest)

    /** 400 means the link is invalid, expired, or already used. */
    @POST("auth/reset-password")
    suspend fun resetPassword(@Body body: ResetPasswordRequest)

    /** Delete the signed-in Account and all its data; the token dies with it. */
    @DELETE("auth/me")
    suspend fun deleteAccount()

    @GET("auth/me")
    suspend fun me(): AccountDto
}
