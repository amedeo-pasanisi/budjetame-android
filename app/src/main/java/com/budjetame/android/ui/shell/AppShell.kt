package com.budjetame.android.ui.shell

import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.data.assistant.AssistantGateway
import com.budjetame.android.data.assistant.AssistantSettingsGateway
import com.budjetame.android.data.backup.BackupGateway
import com.budjetame.android.data.dictation.DictationRecognizer
import com.budjetame.android.data.dictation.DictationTones
import com.budjetame.android.data.category.CategoryGateway
import com.budjetame.android.data.dashboard.DashboardGateway
import com.budjetame.android.data.drafttransaction.DraftTransactionGateway
import com.budjetame.android.data.imports.ImportGateway
import com.budjetame.android.data.locale.LocaleGateway
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.RecurringCostGateway
import com.budjetame.android.data.recurringincome.RecurringIncomeGateway
import com.budjetame.android.data.transaction.TransactionGateway
import com.budjetame.android.data.wallet.WalletGateway
import com.budjetame.android.ui.assistant.AssistantDialog
import com.budjetame.android.ui.assistant.AssistantViewModel
import com.budjetame.android.ui.categories.CategoriesScreen
import com.budjetame.android.ui.common.LedgerJump
import com.budjetame.android.ui.dashboard.DashboardScreen
import com.budjetame.android.ui.recurring.RecurringScreen
import com.budjetame.android.ui.settings.AssistantSettingsSection
import com.budjetame.android.ui.settings.BackupSection
import com.budjetame.android.ui.settings.LanguageSettingsSection
import com.budjetame.android.ui.settings.RestoreSection
import com.budjetame.android.ui.theme.Slate500
import com.budjetame.android.ui.theme.Slate600
import com.budjetame.android.ui.transactions.TransactionsScreen
import com.budjetame.android.ui.undo.UndoStackViewModel
import com.budjetame.android.ui.undo.UndoToastStack
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
    /** The Account's Locale (issue #59): the app's single observable source,
     * fed by the `/auth/me` read that produced [account]. The Settings
     * dialog changes it and the Assistant's Dictation listens in it. */
    localeGateway: LocaleGateway,
    /** The Assistant Settings block of the Settings dialog (ticket #66). */
    assistantSettingsRepository: AssistantSettingsGateway,
    /** The Assistant's turns (ticket #68). */
    assistantRepository: AssistantGateway,
    /** The Assistant's Dictation (ticket #69): the device's speech
     * recognizer (null where it has none: no microphone) and its tones. */
    dictationRecognizer: DictationRecognizer?,
    dictationTones: DictationTones,
    walletRepository: WalletGateway,
    categoryRepository: CategoryGateway,
    dashboardRepository: DashboardGateway,
    transactionRepository: TransactionGateway,
    /** The Draft Transactions pinned above the ledger (ticket #67). */
    draftTransactionRepository: DraftTransactionGateway,
    importRepository: ImportGateway,
    recurringCostRepository: RecurringCostGateway,
    recurringIncomeRepository: RecurringIncomeGateway,
    /** The Backup block of the Settings dialog (ticket #57). */
    backupRepository: BackupGateway,
    /** The device GPS (ticket #29): the Transaction form's location pick,
     * prefill, and first-save attach. */
    location: DeviceLocation,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
    /** Quick Dictation's "open in app" (ticket #65): show the Transactions
     * tab, its Draft Transactions pinned at the top, then [onTransactionsShown]. */
    showTransactions: Boolean = false,
    onTransactionsShown: () -> Unit = {},
) {
    var showSettings by remember { mutableStateOf(false) }

    // The Assistant (ticket #68): its button on every tab while the Account
    // has Assistant Settings, re-read when the Settings dialog closes.
    val assistant: AssistantViewModel = viewModel {
        AssistantViewModel(
            turns = assistantRepository,
            settings = assistantSettingsRepository,
            recognizer = dictationRecognizer,
            tones = dictationTones,
            locale = localeGateway.locale,
        )
    }
    val assistantState by assistant.uiState.collectAsStateWithLifecycle()

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

    // The Undo stack (web ADR-0031, lifted to the app shell by web
    // ADR-0034; ticket #58): one stack for every tab, owned here so a tab
    // switch never forfeits an undo — the pager disposes the pages, never
    // the shell. Held in the Activity's ViewModel store like the pages'
    // ViewModels: it survives configuration changes and goes with the
    // store on sign-out (ADR-0003). Screens report deletes into it; the
    // shell renders it above the bottom bar and runs its Undo handler.
    val undoStack = viewModel { UndoStackViewModel(transactionRepository) }.stack
    val undoState by undoStack.state.collectAsStateWithLifecycle()

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

    LaunchedEffect(showTransactions) {
        if (showTransactions) {
            pagerState.scrollToPage(Tab.Transactions.ordinal)
            onTransactionsShown()
        }
    }

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
                onOpenAssistant = if (assistantState.available) assistant::open else null,
            )
        },
        bottomBar = { BottomTabs(pagerState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            HorizontalPager(
                state = pagerState,
                // The header and the bottom bar stay fixed; only the content
                // area pages. The tag lets the UI tests drag the pager itself
                // (never a scrollable card inside a page).
                modifier = Modifier
                    .fillMaxSize()
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
                            draftTransactionRepository,
                            importRepository,
                            walletRepository,
                            categoryRepository,
                            recurringCostRepository,
                            recurringIncomeRepository,
                            location = location,
                            pendingLedgerJump = pendingLedgerJump,
                            onLedgerJumpConsumed = { pendingLedgerJump = null },
                            undoSink = undoStack,
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

            // Outside the pager, over whichever page shows: the entries stay
            // put while the pages slide underneath.
            UndoToastStack(
                state = undoState,
                onUndo = undoStack::undo,
                onDismiss = undoStack::dismiss,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            email = account.email,
            localeGateway = localeGateway,
            assistantSettingsRepository = assistantSettingsRepository,
            backupRepository = backupRepository,
            // A restore replaces every row the undo entries point at: they
            // are stale by definition (ticket #60, web ADR-0034).
            onRestored = undoStack::clear,
            onClose = {
                showSettings = false
                assistant.refreshAvailability()
            },
            onDeleteAccount = onDeleteAccount,
        )
    }

    if (assistantState.open) {
        AssistantDialog(
            state = assistantState,
            onTextChange = assistant::onTextChange,
            onSend = assistant::send,
            onStartDictation = assistant::startDictation,
            onStopDictation = assistant::stopDictation,
            onClose = assistant::close,
        )
    }
}

@Composable
private fun AppHeader(
    email: String,
    onSignOut: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Null hides the Assistant button: no Assistant Settings. */
    onOpenAssistant: (() -> Unit)?,
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
            if (onOpenAssistant != null) {
                IconButton(onClick = onOpenAssistant) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Assistant",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
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
 * The app's settings (web issue #84): the account email, the Assistant
 * Settings, the Backup's Export all (ticket #57) and Restore (ticket #60),
 * and the destructive
 * account-deletion action behind its own confirm step, with the web app's
 * exact copy.
 */
@Composable
private fun SettingsDialog(
    email: String,
    localeGateway: LocaleGateway,
    assistantSettingsRepository: AssistantSettingsGateway,
    backupRepository: BackupGateway,
    /** A restore succeeded: the shell clears its undo stack. */
    onRestored: () -> Unit,
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
            // Scrolls: the Assistant Settings form can outgrow the screen.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                LanguageSettingsSection(localeGateway)
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                AssistantSettingsSection(assistantSettingsRepository)
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                BackupSection(backupRepository)
                // Next to Export all, in the same dialog: step one's fresh
                // Backup shares the Backup block's held workbook (ticket #60).
                RestoreSection(backupRepository, onRestored = onRestored, onCloseSettings = onClose)
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
