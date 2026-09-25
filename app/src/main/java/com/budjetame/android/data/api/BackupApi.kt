package com.budjetame.android.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET

/**
 * Backup resource (ticket #57): the complete multi-sheet backup workbook
 * — every Account entity (Accounts + Opening Balances, Wallets, Categories,
 * Transactions, Recurring Costs/Incomes, Skips) — as a raw .xlsx download.
 * The response carries the dated filename in Content-Disposition, exactly
 * like the ledger export.
 */
interface BackupApi {

    /**
     * Fetch the full backup workbook for the signed-in Account. Returns
     * the raw .xlsx bytes, not JSON, so Retrofit returns the unparsed
     * response: the file's bytes come from the body, the dated filename
     * from Content-Disposition.
     */
    @GET("backup")
    suspend fun export(): Response<ResponseBody>
}