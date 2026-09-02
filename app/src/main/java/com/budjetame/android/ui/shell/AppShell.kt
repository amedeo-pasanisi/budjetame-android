package com.budjetame.android.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.categories.CategoriesScreen
import com.budjetame.android.ui.dashboard.DashboardScreen
import com.budjetame.android.ui.screens.RecurringScreen
import com.budjetame.android.ui.transactions.TransactionsScreen
import com.budjetame.android.ui.wallets.WalletsScreen
import kotlinx.coroutines.launch

/** The tabs in bottom-nav order, mirroring the web app's AppShell. */
private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("dashboard", "Dashboard", Icons.Filled.Home),
    Tab("wallets", "Wallets", Icons.Filled.AccountBalanceWallet),
    Tab("transactions", "Transactions", Icons.Filled.ReceiptLong),
    Tab("categories", "Categories", Icons.Filled.Category),
    Tab("recurring", "Recurring", Icons.Filled.EventRepeat),
)

@Composable
fun AppShell(
    account: AccountDto,
    walletRepository: WalletGateway,
    categoryRepository: CategoryGateway,
    dashboardRepository: DashboardGateway,
    transactionRepository: TransactionGateway,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
) {
    val navController = rememberNavController()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppHeader(
                email = account.email,
                onSignOut = onSignOut,
                onOpenSettings = { showSettings = true },
            )
        },
        bottomBar = { BottomTabs(navController) },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("dashboard") { DashboardScreen(dashboardRepository) }
            composable("wallets") { WalletsScreen(walletRepository) }
            composable("transactions") { TransactionsScreen(transactionRepository, walletRepository, categoryRepository) }
            composable("categories") { CategoriesScreen(categoryRepository) }
            composable("recurring") { RecurringScreen() }
        }
    }

    if (showSettings) {
        SettingsDialog(
            email = account.email,
            onClose = { showSettings = false },
            onDeleteAccount = onDeleteAccount,
        )
    }
}

@Composable
private fun AppHeader(
    email: String,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Budjetame",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = onSignOut) {
                Text("Sign out", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun BottomTabs(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        TABS.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = {
                    navController.navigate(tab.route) {
                        // Keep-alive (ADR-0002): the tab's back-stack entry —
                        // and its ViewModel — survives while another tab is
                        // shown, so switching back renders instantly from
                        // held data.
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                label = { Text(tab.label, fontSize = 11.sp) },
            )
        }
    }
}

/**
 * The app's settings (web issue #84): the account email and the destructive
 * account-deletion action behind its own confirm step, with the web app's
 * exact copy.
 */
@Composable
private fun SettingsDialog(
    email: String,
    onClose: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
) {
    var confirmOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Settings") },
        text = {
            Column {
                Text(
                    text = email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                Text(
                    text = "Delete account",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Permanently deletes your Account and all its data.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(
                    onClick = { confirmOpen = true },
                    enabled = !deleting,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = if (deleting) "Deleting…" else "Delete account",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmOpen = false },
            title = { Text("Delete account") },
            text = { Text("This permanently deletes your Account and all its data. Continue?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        scope.launch {
                            deleting = true
                            error = null
                            try {
                                onDeleteAccount()
                            } catch (_: Exception) {
                                error = "Could not delete the Account. Please try again."
                                deleting = false
                            }
                        }
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Cancel") }
            },
        )
    }
}
