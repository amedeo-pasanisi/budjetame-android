package com.budjetame.android.ui.shell

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.data.api.AccountDto
import com.budjetame.android.ui.common.ProvideAccountLocale
import com.budjetame.android.ui.settings.FixedLocaleGateway
import com.budjetame.android.ui.settings.NoAssistantGateway
import com.budjetame.android.ui.settings.NoAssistantSettingsGateway
import com.budjetame.android.ui.settings.NoBackupGateway
import com.budjetame.android.ui.settings.SilentDictationTones
import com.budjetame.android.ui.transactions.NoDraftTransactions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composed shell follows the Account's Locale (ADR-0010, issue #62):
 * under `it` the core screens render Italian, and choosing a Language in
 * Settings re-renders the shell — and the open Settings dialog — in place,
 * without restarting anything. The shell sits under [ProvideAccountLocale]
 * fed by the gateway's flow, as `BudjetameApp` does.
 */
@RunWith(AndroidJUnit4::class)
class ShellLocaleTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launchShell(locale: FixedLocaleGateway) {
        composeRule.setContent {
            val code by locale.locale.collectAsState()
            ProvideAccountLocale(code) {
                AppShell(
                    account = AccountDto(id = 1, email = "test@budjetame.de"),
                    localeGateway = locale,
                    assistantSettingsRepository = NoAssistantSettingsGateway(),
                    assistantRepository = NoAssistantGateway(),
                    dictationRecognizer = null,
                    dictationTones = SilentDictationTones(),
                    walletRepository = TabPagerTest.CashWalletGateway(),
                    categoryRepository = TabPagerTest.EmptyCategoryGateway(),
                    dashboardRepository = TabPagerTest.EmptyDashboardGateway(),
                    transactionRepository = TabPagerTest.EmptyTransactionGateway(),
                    draftTransactionRepository = NoDraftTransactions(),
                    importRepository = TabPagerTest.EmptyImportGateway(),
                    recurringCostRepository = TabPagerTest.EmptyRecurringCostGateway(),
                    recurringIncomeRepository = TabPagerTest.EmptyRecurringIncomeGateway(),
                    backupRepository = NoBackupGateway(),
                    location = ShellSilentLocation(),
                    onSignOut = {},
                    onDeleteAccount = {},
                )
            }
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `under the it Locale the shell, the dashboard and Settings render Italian`() {
        launchShell(FixedLocaleGateway("it"))

        composeRule.onNodeWithText("Portafogli").assertExists()
        composeRule.onNodeWithText("Transazioni").assertExists()
        composeRule.onNodeWithText("Esci").assertExists()
        waitForText("PATRIMONIO NETTO")

        // The Wallets page: its Cash section and row label in Italian.
        composeRule.onNodeWithText("Portafogli").performClick()
        waitForText("Contanti")

        composeRule.onNodeWithContentDescription("Impostazioni").performClick()
        composeRule.onAllNodesWithText("Elimina account")[0].assertExists()
        composeRule.onNodeWithText("Chiudi").assertExists()
    }

    @Test
    fun `choosing Italiano in Settings re-renders the shell and the open dialog in place`() {
        val locale = FixedLocaleGateway("en")
        launchShell(locale)
        composeRule.onNodeWithText("Wallets").assertExists()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithText("English").performClick()
        composeRule.onNodeWithText("Italiano").performClick()

        composeRule.waitUntil(5_000) { locale.locale.value == "it" }
        // The open Settings dialog re-rendered, without closing.
        waitForText("Impostazioni")
        composeRule.onNodeWithText("Chiudi").performClick()
        // And so did the shell behind it.
        composeRule.onNodeWithText("Portafogli").assertExists()
        composeRule.onNodeWithText("Wallets").assertDoesNotExist()
    }
}
