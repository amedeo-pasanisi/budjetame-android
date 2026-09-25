package com.budjetame.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.budjetame.android.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Instrumented test for locale-aware string resource resolution (i18n, ticket #62).
 *
 * Uses the testing library's ApplicationProvider to get a Context, wraps it
 * with [wrapContextLocale] for each supported locale, and verifies that key
 * strings resolve correctly in both English and Italian.
 */
class LocaleAwareContextTest {

    @Test
    fun wrapContextLocale_italian_resolves_settings_language() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Lingua", itContext.getString(R.string.language_label))
    }

    @Test
    fun wrapContextLocale_italian_resolves_cancel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Annulla", itContext.getString(R.string.cancel))
    }

    @Test
    fun wrapContextLocale_italian_resolves_save() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Salva", itContext.getString(R.string.save))
    }

    @Test
    fun wrapContextLocale_italian_resolves_loading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Caricamento…", itContext.getString(R.string.loading))
    }

    @Test
    fun wrapContextLocale_italian_resolves_dashboard_title() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Dashboard", itContext.getString(R.string.dashboard_title))
    }

    @Test
    fun wrapContextLocale_italian_resolves_transactions_title() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Transazioni", itContext.getString(R.string.transactions_title))
    }

    @Test
    fun wrapContextLocale_italian_resolves_wallets_title() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Portafogli", itContext.getString(R.string.wallets_title))
    }

    @Test
    fun wrapContextLocale_italian_resolves_categories_title() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Categorie", itContext.getString(R.string.categories_title))
    }

    @Test
    fun wrapContextLocale_english_fallback_returns_english() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val enContext = wrapContextLocale(context, Locale.US)
        assertEquals("Language", enContext.getString(R.string.language_label))
        assertEquals("Cancel", enContext.getString(R.string.cancel))
        assertEquals("Save", enContext.getString(R.string.save))
        assertEquals("Loading…", enContext.getString(R.string.loading))
        assertEquals("Transactions", enContext.getString(R.string.transactions_title))
        assertEquals("Wallets", enContext.getString(R.string.wallets_title))
    }

    @Test
    fun wrapContextLocale_italian_resolves_new_transaction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Nuova transazione", itContext.getString(R.string.new_transaction))
    }

    @Test
    fun wrapContextLocale_italian_resolves_expense_type() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Uscita", itContext.getString(R.string.expense_type))
    }

    @Test
    fun wrapContextLocale_italian_resolves_income_type() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Entrata", itContext.getString(R.string.income_type))
    }

    @Test
    fun wrapContextLocale_italian_resolves_transfer_type() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val itContext = wrapContextLocale(context, Locale.ITALY)
        assertEquals("Trasferimento", itContext.getString(R.string.transfer_type))
    }
}