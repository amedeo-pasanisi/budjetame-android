package com.budjetame.android.ui.login

import android.util.Log
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.budjetame.android.R
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.auth.AuthGateway
import com.budjetame.android.ui.common.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The auth screen's state machine, ported from the web app's LoginForm:
 * the two doors (password, Google) plus the forgot-password flow, with the
 * web's exact error strings.
 */
class LoginViewModel(private val auth: AuthGateway) : ViewModel() {

    enum class Mode { SignIn, SignUp, Forgot }

    data class UiState(
        val mode: Mode = Mode.SignIn,
        val email: String = "",
        val password: String = "",
        val error: UiText? = null,
        val submitting: Boolean = false,
        val resetSent: Boolean = false,
        /** The public Google client id; null until loaded, absent = no button. */
        val googleClientId: String? = null,
        val account: AccountDto? = null,
    ) {
        val signUp: Boolean get() = mode == Mode.SignUp

        /** The submit button works only when the visible fields are filled. */
        val canSubmit: Boolean
            get() = !submitting && email.isNotBlank() && (mode == Mode.Forgot || password.isNotBlank())
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadGoogleConfig()
    }

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }

    fun switchMode(mode: Mode) =
        _uiState.update { it.copy(mode = mode, error = null, resetSent = false) }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return
        if (state.signUp && state.password.length < MIN_PASSWORD_LENGTH) {
            _uiState.update { it.copy(error = UiText.of(R.string.login_error_password_too_short, MIN_PASSWORD_LENGTH)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(submitting = true, error = null) }
            try {
                when (_uiState.value.mode) {
                    Mode.Forgot -> {
                        auth.requestPasswordReset(_uiState.value.email)
                        _uiState.update { it.copy(resetSent = true) }
                    }
                    Mode.SignIn -> _uiState.update { it.copy(account = auth.signIn(it.email, it.password)) }
                    Mode.SignUp -> _uiState.update { it.copy(account = auth.signUp(it.email, it.password)) }
                }
            } catch (error: ApiException) {
                Log.e(TAG, "Auth request failed with HTTP ${error.status}", error)
                _uiState.update { it.copy(error = messageFor(error, _uiState.value.mode)) }
            } catch (error: Exception) {
                Log.e(TAG, "Auth request failed", error)
                _uiState.update { it.copy(error = genericMessageFor(_uiState.value.mode)) }
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    fun onGoogleIdToken(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            try {
                _uiState.update { it.copy(account = auth.signInWithGoogle(idToken)) }
            } catch (error: Exception) {
                Log.e(TAG, "Google sign-in failed", error)
                // The backend rejected the token (POST /auth/google: clock
                // skew, an audience mismatch); the password form remains the
                // fallback.
                _uiState.update {
                    it.copy(error = UiText.of(R.string.login_error_google_rejected))
                }
            }
        }
    }

    /**
     * The credential sheet itself threw — a client-side / registration
     * problem (e.g. no Android OAuth client for this package in Google
     * Cloud); a dismissed sheet never calls this. Keeps the web-parity copy;
     * the password form remains the fallback.
     */
    fun onGoogleError(cause: GetCredentialException) {
        Log.e(TAG, "Google sign-in failed", cause)
        _uiState.update { it.copy(error = UiText.of(R.string.login_error_google)) }
    }

    private fun loadGoogleConfig() {
        viewModelScope.launch {
            try {
                val config = auth.authConfig()
                // The raw id is recorded verbatim; the button hides on blank.
                _uiState.update { it.copy(googleClientId = config.google_client_id) }
            } catch (_: Exception) {
                // No config means no Google button; the password door still works.
            }
        }
    }

    private fun messageFor(error: ApiException, mode: Mode): UiText = UiText.of(
        when (mode) {
            Mode.Forgot -> R.string.login_error_forgot
            Mode.SignIn -> if (error.status == 401) R.string.login_error_sign_in_401 else R.string.login_error_sign_in
            Mode.SignUp -> when (error.status) {
                409 -> R.string.login_error_sign_up_409
                422 -> R.string.login_error_sign_up_422
                else -> R.string.login_error_sign_up
            }
        },
    )

    private fun genericMessageFor(mode: Mode): UiText = UiText.of(
        when (mode) {
            Mode.Forgot -> R.string.login_error_forgot
            Mode.SignIn -> R.string.login_error_sign_in
            Mode.SignUp -> R.string.login_error_sign_up
        },
    )

    companion object {
        private const val TAG = "LoginViewModel"
        private const val MIN_PASSWORD_LENGTH = 8
    }
}
