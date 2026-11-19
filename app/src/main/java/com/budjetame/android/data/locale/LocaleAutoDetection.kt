package com.budjetame.android.data.locale

import android.content.Context
import android.content.res.Resources
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Which Accounts this device has already auto-detected the Locale for. */
interface LocaleDetectionStore {
    fun hasRun(accountId: Int): Boolean
    fun markRan(accountId: Int)
}

/** The device's own record, in plain preferences: an Account id is no secret. */
class PrefsLocaleDetectionStore(context: Context) : LocaleDetectionStore {

    private val prefs = context.getSharedPreferences("locale_auto_detect", Context.MODE_PRIVATE)

    override fun hasRun(accountId: Int): Boolean = prefs.getBoolean(key(accountId), false)

    override fun markRan(accountId: Int) {
        prefs.edit().putBoolean(key(accountId), true).apply()
    }

    private fun key(accountId: Int) = "account_$accountId"
}

/**
 * Auto-detect the signed-in Account's Locale once on this device (issue #59,
 * [localeAutoDetect]): an `en` Account on an Italian device is switched to
 * `it` through the [LocaleGateway]. A switch the server refused is not
 * remembered, so the next start tries again; it never surfaces an error.
 */
class LocaleAutoDetection(
    private val gateway: LocaleGateway,
    private val store: LocaleDetectionStore,
    /** The device's own language — the system's, not the app's. */
    private val deviceLocale: () -> Locale = { Resources.getSystem().configuration.locales[0] },
) {

    suspend fun run(accountId: Int) {
        val decision = localeAutoDetect(
            accountLocale = gateway.locale.value,
            deviceLocale = deviceLocale(),
            alreadyRan = store.hasRun(accountId),
        )
        when (decision) {
            LocaleAutoDetect.ALREADY_RAN -> Unit
            LocaleAutoDetect.KEEP -> store.markRan(accountId)
            LocaleAutoDetect.SWITCH_TO_ITALIAN -> try {
                gateway.changeLocale(LOCALE_IT)
                store.markRan(accountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Silent: the Locale stays `en` and the next start retries.
            }
        }
    }
}
