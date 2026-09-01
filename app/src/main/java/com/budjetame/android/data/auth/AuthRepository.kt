package com.budjetame.android.data.auth

import com.budjetame.android.data.Session
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.api.AuthConfigDto
import com.budjetame.android.data.api.CredentialsRequest
import com.budjetame.android.data.api.EmailRequest
import com.budjetame.android.data.api.GoogleTokenRequest
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/**
 * The auth operations screens call (UI-independent — a future Gemini App
 * Functions capability can call the same functions).
 */
interface AuthGateway {
    suspend fun signIn(email: String, password: String): AccountDto
    suspend fun signUp(email: String, password: String): AccountDto
    suspend fun signInWithGoogle(idToken: String): AccountDto
    suspend fun authConfig(): AuthConfigDto
    suspend fun requestPasswordReset(email: String)
}

/**
 * The API-backed AuthGateway: a successful sign-in stores the JWT in the
 * session, then fetches the Account the token belongs to.
 */
class ApiAuthRepository(
    private val api: AuthApi,
    private val session: Session,
) : AuthGateway {

    override suspend fun signIn(email: String, password: String): AccountDto =
        call { api.login(CredentialsRequest(email, password)) }.let { token ->
            session.save(token.access_token)
            call { api.me() }
        }

    override suspend fun signUp(email: String, password: String): AccountDto =
        call { api.register(CredentialsRequest(email, password)) }.let { token ->
            session.save(token.access_token)
            call { api.me() }
        }

    override suspend fun signInWithGoogle(idToken: String): AccountDto =
        call { api.google(GoogleTokenRequest(idToken)) }.let { token ->
            session.save(token.access_token)
            call { api.me() }
        }

    override suspend fun authConfig(): AuthConfigDto = call { api.config() }

    override suspend fun requestPasswordReset(email: String) {
        call { api.forgotPassword(EmailRequest(email)) }
    }

    /**
     * The Account for the stored token, or null when there is no token or
     * the backend rejected it (the session is cleared in that case).
     */
    suspend fun fetchCurrentAccount(): AccountDto? {
        val token = session.token ?: return null
        return try {
            call { api.me() }
        } catch (error: ApiException) {
            if (error.status == 401) {
                session.clear()
                null
            } else {
                throw error
            }
        }
    }

    /** Delete the signed-in Account and all its data (web issue #84). */
    suspend fun deleteAccount() {
        call { api.deleteAccount() }
    }

    fun signOut() = session.clear()

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
