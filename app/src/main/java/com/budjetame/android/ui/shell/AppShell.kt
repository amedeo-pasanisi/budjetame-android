package com.budjetame.android.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.categories.CategoriesScreen
import com.budjetame.android.ui.dashboard.DashboardScreen
import com.budjetame.android.ui.recurring.RecurringScreen
import com.budjetame.android.ui.transactions.TransactionsScreen
import com.budjetame.android.ui.wallets.WalletsScreen
import kotlinx.coroutines.launch

/** The five tabs in bottom-nav order, mirroring the web app's AppShell. The
 * enum's name is the pager page's stable identity: it keys the per-tab
 * saveable-state registry (ADR-0003) and the pager never reorders it. */
private enum class Tab(val label: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Home),
    Wallets("Wallets", Icons.Filled.AccountBalanceWallet),
    Transactions("Transactions", Icons.Filled.ReceiptLong),
    Categories("Categories", Icons.Filled.Category),
    Recurring("Recurring", Icons.Filled.EventRepeat),
}

private val TABS = Tab.entries

@Composable
fun AppShell(
    account: AccountDto,
    walletRepository: WalletGateway,
    categoryRepository: CategoryGateway,
    dashboardRepository: DashboardGateway,
    transactionRepository: TransactionGateway,
    importRepository: ImportGateway,
    recurringCostRepository: RecurringCostGateway,
    recurringIncomeRepository: RecurringIncomeGateway,
    /** The device GPS (ticket #29): the Transaction form's location pick,
     * prefill, and first-save attach. */
    location: DeviceLocation,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    // The five tabs in one finger-following pager (ADR-0003): the content
    // drags with the finger, a release snaps to the nearest tab, a fling
    // crosses one tab, and the bottom bar's taps animate to the page. The
    // pages' ViewModels resolve to the Activity's store now that the nav
    // back stack is gone — they survive page disposal, so returning to a
    // tab renders instantly from held data (ADR-0002), and the app clears
    // that store itself on sign-out / account deletion (ADR-0003). Only
    // the current page stays composed once settled; the per-tab transient
    // UI state (scroll positions, toggles — whatever rememberSaveable
    // holds) is retained in a SaveableStateHolder keyed per tab, replacing
    // the back-stack entries' saved-state keep-alive.
    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val tabState = rememberSaveableStateHolder()

    Scaffold(
        topBar = {
            AppHeader(
                email = account.email,
                onSignOut = onSignOut,
                onOpenSettings = { showSettings = true },
            )
        },
        bottomBar = { BottomTabs(pagerState) },
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            // The header and the bottom bar stay fixed; only the content
            // area pages. The tag lets the UI tests drag the pager itself
            // (never a scrollable card inside a page).
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("tab-pager"),
        ) { page ->
            val tab = TABS[page]
            tabState.SaveableStateProvider(key = tab.name) {
                when (tab) {
                    Tab.Dashboard -> DashboardScreen(dashboardRepository)
                    Tab.Wallets -> WalletsScreen(walletRepository)
                    Tab.Transactions -> TransactionsScreen(
                        transactionRepository,
                        importRepository,
                        walletRepository,
                        categoryRepository,
                        recurringCostRepository,
                        recurringIncomeRepository,
                        location = location,
                    )
                    Tab.Categories -> CategoriesScreen(categoryRepository)
                    Tab.Recurring -> RecurringScreen(recurringCostRepository, recurringIncomeRepository)
                }
            }
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
    // The Surface stays full-bleed so its color reaches the very top of the
    // screen behind the transparent status bar (edge-to-edge, ticket #34);
    // only the Row's content clears the status-bar inset — the content keeps
    // its own 16/8dp padding inside the safe area, and the Scaffold still
    // measures the whole header into its innerPadding, so nothing below
    // shifts twice.
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
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
private fun BottomTabs(pagerState: PagerState) {
    val scope = rememberCoroutineScope()

    Column {
        // The web shell's border-t border-slate-200 above the tab bar.
        HorizontalDivider()
        NavigationBar {
            TABS.forEachIndexed { index, tab ->
                NavigationBarItem(
                    // currentPage, not settledPage: the selection follows
                    // the drag live and only settles where the page does.
                    selected = pagerState.currentPage == index,
                    onClick = {
                        // A tap glides to the tab (~250 ms, ADR-0003), so
                        // taps and drags feel like one motion.
                        scope.launch {
                            pagerState.animateScrollToPage(
                                page = index,
                                animationSpec = tween(durationMillis = 250),
                            )
                        }
                    },
                    icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                    label = { Text(tab.label, fontSize = 11.sp) },
                )
            }
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
