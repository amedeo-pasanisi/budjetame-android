package com.budjetame.android.ui.common

import android.content.res.Configuration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.budjetame.android.R
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Account Locale drives string resources (ADR-0010, issue #62),
 * whatever the device language: `values-it` for `it`, the English `values`
 * for `en` and for any key `values-it` lacks; a new window re-applies it;
 * and a Locale change re-renders in place. The "device" is simulated by
 * forcing the outer configuration, so the tests hold on any emulator
 * language.
 */
@RunWith(AndroidJUnit4::class)
class AccountLocaleResourcesTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The composition as a device set to [locale] would see it. */
    @Composable
    private fun Device(locale: Locale, content: @Composable () -> Unit) {
        val base = LocalContext.current
        val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
        val context = base.createConfigurationContext(configuration)
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides configuration,
            LocalResources provides context.resources,
            content = content,
        )
    }

    @Test
    fun `an Italian Account renders Italian on an English device`() {
        composeRule.setContent {
            Device(Locale.US) {
                ProvideAccountLocale("it") { Text(stringResource(R.string.settings_title)) }
            }
        }
        composeRule.onNodeWithText("Impostazioni").assertExists()
    }

    @Test
    fun `an English Account renders English on an Italian device`() {
        composeRule.setContent {
            Device(Locale.ITALY) {
                ProvideAccountLocale("en") { Text(stringResource(R.string.settings_title)) }
            }
        }
        composeRule.onNodeWithText("Settings").assertExists()
        composeRule.onNodeWithText("Impostazioni").assertDoesNotExist()
    }

    @Test
    fun `a key values-it lacks falls back to English`() {
        // record_label is deliberately English-only (the system-drawn tile label).
        composeRule.setContent {
            Device(Locale.US) {
                ProvideAccountLocale("it") { Text(stringResource(R.string.record_label)) }
            }
        }
        composeRule.onNodeWithText("Record Budjetame").assertExists()
    }

    @Test
    fun `a dialog window re-applies the Account Locale`() {
        composeRule.setContent {
            ProvideAccountLocale("it") {
                AlertDialog(
                    onDismissRequest = {},
                    title = localized { Text(stringResource(R.string.settings_title)) },
                    confirmButton = localized { Text(stringResource(R.string.common_close)) },
                )
            }
        }
        composeRule.onNodeWithText("Impostazioni").assertExists()
        composeRule.onNodeWithText("Chiudi").assertExists()
    }

    @Test
    fun `a Locale change re-renders the screen and an open dialog in place`() {
        var code by mutableStateOf("en")
        composeRule.setContent {
            Device(Locale.US) {
                ProvideAccountLocale(code) {
                    Text(stringResource(R.string.shell_tab_wallets))
                    AlertDialog(
                        onDismissRequest = {},
                        title = localized { Text(stringResource(R.string.settings_title)) },
                        confirmButton = localized { Text(stringResource(R.string.common_close)) },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Wallets").assertExists()
        composeRule.onNodeWithText("Settings").assertExists()

        code = "it"

        composeRule.onNodeWithText("Portafogli").assertExists()
        composeRule.onNodeWithText("Impostazioni").assertExists()
        composeRule.onNodeWithText("Chiudi").assertExists()
        composeRule.onNodeWithText("Wallets").assertDoesNotExist()
    }
}
