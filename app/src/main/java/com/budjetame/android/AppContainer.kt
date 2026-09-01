package com.budjetame.android

import android.content.Context
import com.budjetame.android.data.Session
import com.budjetame.android.data.TokenStore
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.auth.ApiAuthRepository
import com.budjetame.android.data.wallet.ApiWalletRepository

/**
 * Manual composition root (ADR-0001 keeps the app single-module; constructor
 * injection is enough at this size — no DI framework). Builds the session,
 * the transport, and the repositories once per process.
 */
class AppContainer(context: Context) {

    private val tokenStore = TokenStore(context)

    val session = Session(tokenStore)

    private val api = ApiClient(BuildConfig.API_BASE_URL) { session.token }

    val authRepository = ApiAuthRepository(api.create(AuthApi::class.java), session)

    val walletRepository = ApiWalletRepository(api.create(WalletApi::class.java))
}
