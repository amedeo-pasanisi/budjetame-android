package com.budjetame.android.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.auth.AuthGateway
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

/**
 * The auth screen, ported from the web app's LoginForm: the two doors
 * (password, Google) plus the forgot-password flow, with the web's exact
 * copy and error strings.
 */
@Composable
fun LoginScreen(
    auth: AuthGateway,
    onSignedIn: (AccountDto) -> Unit,
) {
    val viewModel: LoginViewModel = viewModel { LoginViewModel(auth) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.account) {
        state.account?.let(onSignedIn)
    }

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Budjetame",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = when {
                        state.mode == LoginViewModel.Mode.Forgot ->
                            "We will email you a link to reset your password."
                        state.signUp -> "Create an Account to see your money."
                        else -> "Sign in to see your money."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (state.mode != LoginViewModel.Mode.Forgot) {
                    GoogleButton(
                        clientId = state.googleClientId,
                        onIdToken = viewModel::onGoogleIdToken,
                        onError = viewModel::onGoogleError,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 16.dp),
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            text = "or",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }
                }

                if (state.resetSent) {
                    Column {
                        Text(
                            text = "Check your inbox — the link works once and expires soon.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Button(
                            onClick = { viewModel.switchMode(LoginViewModel.Mode.SignIn) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                        ) {
                            Text("Back to sign in")
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::onEmailChange,
                        label = { Text("Email") },
                        placeholder = { Text("you@example.com") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    )
                    if (state.mode != LoginViewModel.Mode.Forgot) {
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChange,
                            label = { Text("Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp),
                        )
                    }
                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Button(
                        onClick = viewModel::submit,
                        enabled = state.canSubmit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        Text(
                            when {
                                state.submitting && state.mode == LoginViewModel.Mode.Forgot -> "Sending…"
                                state.submitting && state.signUp -> "Creating…"
                                state.submitting -> "Signing in…"
                                state.mode == LoginViewModel.Mode.Forgot -> "Send reset link"
                                state.signUp -> "Create account"
                                else -> "Sign in"
                            },
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    when (state.mode) {
                        LoginViewModel.Mode.SignIn -> ModeLinkRow(
                            text = "Forgot your password?",
                            action = "Reset it",
                            onClick = { viewModel.switchMode(LoginViewModel.Mode.Forgot) },
                        )
                        LoginViewModel.Mode.Forgot -> ModeLinkRow(
                            text = "Remembered it?",
                            action = "Sign in",
                            onClick = { viewModel.switchMode(LoginViewModel.Mode.SignIn) },
                        )
                        LoginViewModel.Mode.SignUp -> ModeLinkRow(
                            text = "Already have an Account?",
                            action = "Sign in",
                            onClick = { viewModel.switchMode(LoginViewModel.Mode.SignIn) },
                        )
                    }
                }
                if (state.mode == LoginViewModel.Mode.SignIn) {
                    ModeLinkRow(
                        text = "Don't have an Account?",
                        action = "Sign up",
                        onClick = { viewModel.switchMode(LoginViewModel.Mode.SignUp) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * A shared row for the auth screen's mode-switch links, mirroring the web
 * app's "Forgot your password? Reset it" style lines.
 */
@Composable
private fun ModeLinkRow(
    text: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClick, contentPadding = PaddingValues(4.dp)) {
            Text(action, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * The "Sign in with Google" button (web issue #81): the client id comes from
 * the backend's /auth/config (null = no button). Uses Credential Manager; a
 * dismissed sheet is silent, any other failure surfaces the error — the
 * password form remains the fallback.
 */
@Composable
private fun GoogleButton(
    clientId: String?,
    onIdToken: (String) -> Unit,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (clientId.isNullOrBlank()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Button(
        onClick = {
            scope.launch {
                try {
                    val credentialManager = CredentialManager.create(context)
                    val option = GetGoogleIdOption.Builder()
                        .setServerClientId(clientId)
                        .setFilterByAuthorizedAccounts(false)
                        .setAutoSelectEnabled(true)
                        .build()
                    val request = GetCredentialRequest.Builder()
                        .addCredentialOption(option)
                        .build()
                    val response: GetCredentialResponse =
                        credentialManager.getCredential(context, request)
                    if (response.credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        val idToken =
                            GoogleIdTokenCredential.createFrom(response.credential.data).idToken
                        onIdToken(idToken)
                    }
                } catch (_: GetCredentialCancellationException) {
                    // The user dismissed the sheet — not an error.
                } catch (_: GetCredentialException) {
                    onError()
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text("Sign in with Google")
    }
}
