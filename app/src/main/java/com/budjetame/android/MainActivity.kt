package com.budjetame.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.budjetame.android.ui.theme.BudjetameTheme

class MainActivity : ComponentActivity() {

    /** Quick Dictation's "open in app" (ticket #65) asked for the
     * Transactions tab, until the shell shows it. */
    private var showTransactions by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) showTransactions = intent.wantsTransactions()
        val container = (application as BudjetameApplication).container
        setContent {
            BudjetameTheme {
                BudjetameApp(
                    container,
                    showTransactions = showTransactions,
                    onTransactionsShown = { showTransactions = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.wantsTransactions()) showTransactions = true
    }

    companion object {
        private const val EXTRA_SHOW_TRANSACTIONS = "com.budjetame.android.SHOW_TRANSACTIONS"

        private fun Intent.wantsTransactions() = getBooleanExtra(EXTRA_SHOW_TRANSACTIONS, false)

        /** Open the app over its running task, if any: on the Transactions
         * tab when [showTransactions] (the Draft Transactions pinned at its top). */
        fun open(context: Context, showTransactions: Boolean) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    .putExtra(EXTRA_SHOW_TRANSACTIONS, showTransactions),
            )
        }
    }
}
