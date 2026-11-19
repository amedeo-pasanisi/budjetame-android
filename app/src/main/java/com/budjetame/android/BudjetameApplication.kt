package com.budjetame.android

import android.app.Application

/**
 * The process: it holds the one [AppContainer], so the app and Quick
 * Dictation (ticket #65) share one session, one transport and one
 * DataVersion — a Draft Transaction the card writes reloads the open Transactions tab.
 */
class BudjetameApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
