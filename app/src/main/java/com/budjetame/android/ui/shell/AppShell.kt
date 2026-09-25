package com.budjetame.android.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.auth.LocaleGateway
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.categories.CategoriesScreen
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.ui.dashboard.DashboardScreen
import com.budjetame.android.ui.recurring.RecurringScreen
import com.budjetame.android.ui.theme.Slate500
import com.budjetame.android.ui.theme.Slate600
import com.budjetame.android.ui.transactions.TransactionsScreen
import com.budjetame.android.ui.wallets.WalletsScreen
import com.budjetame.android.data.transaction.shareExportFile
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.budjetame.android.ui.imports.readPickedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    /** The backup export (ticket #57): downloads the complete multi-sheet
     * backup workbook and shares it via the system share sheet. */
    backupRepository: BackupGateway,
    /** The locale gateway (i18n, ticket #59): reads and updates the Account locale. */
    localeRepository: LocaleGateway,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    // The pending restore-complete flag (issue #60): when set, the
    // Transactions screen's undo snackbar stack is cleared. Consumed
    // by the Transactions screen on first mount after restore.
    var restorePending by remember { mutableStateOf(false) }

    // The pending ledger jump (ADR-0004, ticket #44, extended to the
    // Recurring cards by web ADR-0026 / ticket #46): a Wallet, Category,
    // or Recurring definition
    // row asked for the Transactions ledger pre-filtered to it, and the
    // request waits here until the Transactions screen applies it and calls
    // back. Shell state, not screen state: the request can arrive while the
    // Transactions page is disposed (only the current page stays composed,
    // ADR-0003) or before it was ever visited, and it must not be lost
    // while the screen is showing an Import Draft. A newer request replaces
    // an unconsumed one.
    var pendingLedgerJump by remember { mutableStateOf<LedgerJump?>(null) }
    val scope = rememberCoroutineScope()

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

    // Send a ledger jump (ADR-0004, web ADR-0026): hold the request
    // pending and glide to
    // the Transactions page — the screen applies it on first mount (as
    // initial state) or, when already alive, through the apply-and-consume
    // effect. The glide is the bottom-tab tap's own 250 ms tween
    // (ADR-0003). A newer request replaces an unconsumed one.
    val requestLedgerJump: (LedgerJump) -> Unit = { jump ->
        pendingLedgerJump = jump
        scope.launch {
            pagerState.animateScrollToPage(
                page = Tab.Transactions.ordinal,
                animationSpec = tween(durationMillis = 250),
            )
        }
    }


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
                    Tab.Dashboard -> DashboardScreen(
                        dashboardRepository,
                        recurringCostRepository,
                        recurringIncomeRepository,
                    )
                    Tab.Wallets -> WalletsScreen(
                        walletRepository,
                        onLedgerJump = requestLedgerJump,
                    )
                    Tab.Transactions -> TransactionsScreen(
                        transactionRepository,
                        importRepository,
                        walletRepository,
                        categoryRepository,
                        recurringCostRepository,
                        recurringIncomeRepository,
                        location = location,
                        pendingLedgerJump = pendingLedgerJump,
                        onLedgerJumpConsumed = { pendingLedgerJump = null },
                        restorePending = restorePending,
                        onRestoreConsumed = { restorePending = false },
                    )
                    Tab.Categories -> CategoriesScreen(
                        categoryRepository,
                        onLedgerJump = requestLedgerJump,
                    )
                    Tab.Recurring -> RecurringScreen(
                        recurringCostRepository,
                        recurringIncomeRepository,
                        onLedgerJump = requestLedgerJump,
                    )
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            email = account.email,
            onClose = { showSettings = false },
            onDeleteAccount = onDeleteAccount,
            backupRepository = backupRepository,
            localeRepository = localeRepository,
            onRestoreSuccess = { restorePending = true },
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
                    // The web header's email line: text-xs text-slate-500
                    // (ticket #44's exact mapping).
                    fontSize = 12.sp,
                    color = Slate500,
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
            OutlinedButton(
                onClick = onSignOut,
                // The web header's bordered button: rounded-lg with a
                // slate-300 border and slate-600 text (ticket #44).
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate600),
            ) {
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
 * The app's settings (web issue #84, ticket #57): the account email,
 * the destructive account-deletion action behind its own confirm step,
 * and the Export all action that downloads the full backup workbook and
 * surfaces it via the system share sheet.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsDialog(
    email: String,
    onClose: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
    backupRepository: BackupGateway,
    localeRepository: LocaleGateway,
    onRestoreSuccess: () -> Unit,
) {
    var confirmOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }
    var localeExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val localeViewModel: SettingsLocaleViewModel = viewModel {
        SettingsLocaleViewModel(localeRepository)
    }
    val localeState by localeViewModel.state.collectAsStateWithLifecycle()

    // Restore from backup (issue #60)
    val restoreViewModel: RestoreViewModel = viewModel {
        RestoreViewModel(backupRepository)
    }
    val restoreState by restoreViewModel.state.collectAsStateWithLifecycle()

    // The SAF document picker for backup workbooks (.xlsx files), reusing
    // the same OpenDocument pattern from ImportScreen.
    val restoreFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    readPickedFile(context.contentResolver, uri)
                }
                restoreViewModel.onFilePicked(picked?.first, picked?.second)
            }
        }
    }

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

                // Language picker (i18n, ticket #59)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Text(
                    text = "Language",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                ExposedDropdownMenuBox(
                    expanded = localeExpanded,
                    onExpandedChange = { localeExpanded = it },
                ) {
                    Text(
                        text = if (localeState.currentLocale == "it") "Italiano" else "English",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .padding(top = 4.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = localeExpanded,
                        onDismissRequest = { localeExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("English") },
                            onClick = {
                                localeExpanded = false
                                localeViewModel.selectLocale("en")
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                        DropdownMenuItem(
                            text = { Text("Italiano") },
                            onClick = {
                                localeExpanded = false
                                localeViewModel.selectLocale("it")
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
                if (localeState.updating) {
                    Text(
                        text = "Saving…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                localeState.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Export all (ticket #57)
                Text(
                    text = "Export all",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Downloads the complete backup workbook with all your data.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            exporting = true
                            exportError = null
                            try {
                                val export = backupRepository.exportBackup()
                                val errorMsg = shareExportFile(context, export)
                                if (errorMsg != null) {
                                    exportError = errorMsg
                                }
                            } catch (_: Exception) {
                                exportError = "Could not export the backup workbook."
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    enabled = !exporting,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = if (exporting) "Exporting…" else "Export all",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                exportError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Restore from backup (issue #60)
                Text(
                    text = "Restore from backup",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Pick a backup workbook to atomically replace all your data.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                TextButton(
                    onClick = {
                        restoreViewModel.reset()
                        restoreFilePicker.launch(RESTORE_MIME_TYPES)
                    },
                    enabled = restoreState.phase == RestorePhase.IDLE ||
                        restoreState.phase == RestorePhase.ERROR,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        text = "Restore from backup…",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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

    // Restore flow (issue #60)
    if (restoreState.phase != RestorePhase.IDLE) {
        RestoreDialog(
            state = restoreState,
            viewModel = restoreViewModel,
            onExportHandled = {
                val export = restoreState.exportFile ?: return@RestoreDialog
                val errorMsg = shareExportFile(context, export)
                if (errorMsg != null) {
                    restoreViewModel.onExportErrorHandled(errorMsg)
                } else {
                    restoreViewModel.onExportHandled()
                }
            },
            onDismiss = {
                restoreViewModel.dismiss()
            },
            onSuccess = {
                restoreViewModel.dismiss()
                onRestoreSuccess()
            },
        )
    }
}

/**
 * The restore flow's dialog (issue #60): shows the two-step confirmation
 * with an optional fresh Export all before the commit, the in-flight
 * progress, and the success or error result.
 */
@Composable
private fun RestoreDialog(
    state: RestoreState,
    viewModel: RestoreViewModel,
    onExportHandled: () -> Unit,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    when (state.phase) {
        RestorePhase.PICKED -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Restore from backup") },
                text = {
                    Column {
                        Text(
                            text = "This will REPLACE all your Account data with the contents of the backup workbook.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "File: ${state.fileName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.exportError != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = state.exportError,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "You can export a fresh backup of the current data first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.onFirstConfirm() },
                    ) {
                        Text("Continue to restore")
                    }
                },
                dismissButton = {
                    Row {
                        if (state.exporting) {
                            Text(
                                text = "Exporting…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        TextButton(
                            onClick = { viewModel.onOfferExport() },
                            enabled = !state.exporting && state.exportFile == null,
                        ) {
                            Text("Export all")
                        }
                        // Show share button when a fresh export is ready
                        if (state.exportFile != null) {
                            TextButton(
                                onClick = onExportHandled,
                            ) {
                                Text("Share export")
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onDismiss,
                        ) {
                            Text("Cancel", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
        RestorePhase.FIRST_CONFIRMED -> {
            AlertDialog(
                onDismissRequest = { viewModel.cancelSecondConfirm() },
                title = { Text("Are you absolutely sure?") },
                text = {
                    Text(
                        text = "This replaces ALL your data — all Transactions, Wallets, Categories, " +
                            "Recurring Costs/Incomes, and Skips — with the contents of the backup. " +
                            "Account email, credentials, and language are unaffected. This is irreversible.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.onSecondConfirm() }) {
                        Text("Restore", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelSecondConfirm() }) {
                        Text("Cancel")
                    }
                },
            )
        }
        RestorePhase.RESTORING -> {
            AlertDialog(
                onDismissRequest = {}, // Can't dismiss while in flight
                title = { Text("Restoring…") },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Replacing your data from the backup…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {},
            )
        }
        RestorePhase.SUCCESS -> {
            AlertDialog(
                onDismissRequest = onSuccess,
                title = { Text("Restored") },
                text = {
                    Column {
                        Text(
                            text = "All Account data has been replaced from the backup.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (state.originWarning) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The backup's origin marker doesn't match your Account — " +
                                    "it may have been created elsewhere. Data was still restored.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onSuccess) {
                        Text("Done")
                    }
                },
            )
        }
        RestorePhase.ERROR -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Restore failed") },
                text = {
                    Text(
                        text = state.error ?: "Could not restore from the backup file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                },
            )
        }
        RestorePhase.IDLE -> { /* no dialog shown */ }
    }
}

/** The backup workbook MIME types the restore file picker accepts (issue
 * #60): .xlsx workbooks — reuses the same OpenDocument pattern from
 * ImportScreen. */
private val RESTORE_MIME_TYPES = arrayOf(
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/octet-stream",
)
