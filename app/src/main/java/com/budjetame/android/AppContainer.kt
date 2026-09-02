package com.budjetame.android

import android.content.Context
import com.budjetame.android.data.Session
import com.budjetame.android.data.TokenStore
import com.budjetame.android.data.api.ApiClient
import com.budjetame.android.data.api.AuthApi
import com.budjetame.android.data.api.CategoryApi
import com.budjetame.android.data.api.DashboardApi
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringIncomeApi
import com.budjetame.android.data.api.ImportApi
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.WalletApi
import com.budjetame.android.data.auth.ApiAuthRepository
import com.budjetame.android.data.category.ApiCategoryRepository
import com.budjetame.android.data.dashboard.ApiDashboardRepository
import com.budjetame.android.data.imports.ApiImportRepository
import com.budjetame.android.data.location.AndroidDeviceLocation
import com.budjetame.android.data.location.DeviceLocation
import com.budjetame.android.data.recurringcost.ApiRecurringCostRepository
import com.budjetame.android.data.recurringincome.ApiRecurringIncomeRepository
import com.budjetame.android.data.transaction.ApiTransactionRepository
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

    val categoryRepository = ApiCategoryRepository(api.create(CategoryApi::class.java))

    val dashboardRepository = ApiDashboardRepository(api.create(DashboardApi::class.java))

    val transactionRepository = ApiTransactionRepository(api.create(TransactionApi::class.java))

    val importRepository = ApiImportRepository(api.create(ImportApi::class.java))

    val recurringCostRepository = ApiRecurringCostRepository(api.create(RecurringCostApi::class.java))

    val recurringIncomeRepository = ApiRecurringIncomeRepository(api.create(RecurringIncomeApi::class.java))

    /** The device GPS (ticket #29): the Transaction form's location pick,
     * prefill, and first-save attach. */
    val deviceLocation: DeviceLocation = AndroidDeviceLocation(context)
}
