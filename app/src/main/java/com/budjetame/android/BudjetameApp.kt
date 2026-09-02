package com.budjetame.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.ui.login.LoginScreen
import com.budjetame.android.ui.shell.AppShell

/** The auth state machine, mirroring the web app's App.tsx. */
sealed interface AuthState {
    data object Checking : AuthState

    /** A stored token exists but the account check could not run (offline). */
    data object CheckingFailed : AuthState

    data object SignedOut : AuthState

    data class SignedIn(val account: AccountDto) : AuthState
}

@Composable
fun BudjetameApp(container: AppContainer) {
    var authState by remember { mutableStateOf<AuthState>(AuthState.Checking) }
    var checkAttempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(checkAttempt) {
        authState = AuthState.Checking
        authState = try {
            val account = container.authRepository.fetchCurrentAccount()
            if (account == null) AuthState.SignedOut else AuthState.SignedIn(account)
        } catch (_: Exception) {
            AuthState.CheckingFailed
        }
    }

    when (val state = authState) {
        AuthState.Checking -> CheckingScreen()
        AuthState.CheckingFailed -> CheckingFailedScreen(onRetry = { checkAttempt++ })
        AuthState.SignedOut -> LoginScreen(
            auth = container.authRepository,
            onSignedIn = { account -> authState = AuthState.SignedIn(account) },
        )
        is AuthState.SignedIn -> AppShell(
            account = state.account,
            walletRepository = container.walletRepository,
            categoryRepository = container.categoryRepository,
            dashboardRepository = container.dashboardRepository,
            transactionRepository = container.transactionRepository,
            onSignOut = {
                container.authRepository.signOut()
                authState = AuthState.SignedOut
            },
            onDeleteAccount = {
                container.authRepository.deleteAccount()
                container.authRepository.signOut()
                authState = AuthState.SignedOut
            },
        )
    }
}

@Composable
private fun CheckingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Signing you in…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckingFailedScreen(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Could not connect to Budjetame.",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Check your connection and try again.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text("Retry")
        }
    }
}
