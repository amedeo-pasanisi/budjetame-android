package com.budjetame.android.data.auth

import com.budjetame.android.data.Session
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.api.AuthConfigDto
import com.budjetame.android.data.api.CredentialsRequest
import com.budjetame.android.data.api.EmailRequest
import com.budjetame.android.data.api.GoogleTokenRequest
import com.budjetame.android.data.api.LanguageUpdateRequest
import com.budjetame.android.data.api.toApiException
import com.budjetame.android.data.locale.LastLocaleStore
import com.budjetame.android.data.locale.LocaleGateway
import com.budjetame.android.data.locale.localeCode
import com.budjetame.android.data.locale.signedOutLocale
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * It is also the Account's [LocaleGateway] (issue #59): every Account it
 * reads from `GET /auth/me` publishes its language on [locale], so the
 * Locale costs no request of its own.
 *
 * Before any Account is read — the login screen, a cold start — [locale]
 * is the last Locale this device saw ([lastLocale]), or the device
 * language when none is stored (issue #63). Every published Locale is
 * stored, and sign-out keeps it, so the login screen speaks the language
 * of the Account that just left.
 */
class ApiAuthRepository(
    private val api: AuthApi,
    private val session: Session,
    private val lastLocale: LastLocaleStore = LastLocaleStore.None,
    deviceLocale: Locale = Locale.US,
) : AuthGateway, LocaleGateway {

    private val _locale = MutableStateFlow(signedOutLocale(lastLocale.read(), deviceLocale))
    override val locale: StateFlow<String> = _locale.asStateFlow()

    override suspend fun signIn(email: String, password: String): AccountDto =
        call { api.login(CredentialsRequest(email, password)) }.let { token ->
            session.save(token.access_token)
            me()
        }

    override suspend fun signUp(email: String, password: String): AccountDto =
        call { api.register(CredentialsRequest(email, password)) }.let { token ->
            session.save(token.access_token)
            me()
        }

    override suspend fun signInWithGoogle(idToken: String): AccountDto =
        call { api.google(GoogleTokenRequest(idToken)) }.let { token ->
            session.save(token.access_token)
            me()
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
            me()
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

    override suspend fun changeLocale(code: String) {
        call { api.updateLanguage(LanguageUpdateRequest(code)) }
        publishLocale(code)
    }

    /** Clear the session. The Locale stays the last known one (issue #63):
     * the login screen that follows renders in it. */
    fun signOut() {
        session.clear()
    }

    /** The current Account, its Locale published on the way. */
    private suspend fun me(): AccountDto =
        call { api.me() }.also { publishLocale(it.language) }

    private fun publishLocale(serverValue: String) {
        val code = localeCode(serverValue)
        _locale.value = code
        lastLocale.write(code)
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
