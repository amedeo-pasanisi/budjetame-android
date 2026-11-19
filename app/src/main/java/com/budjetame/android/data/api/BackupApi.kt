package com.budjetame.android.data.api

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET

/**
 * Backup resource (ticket #57, web #112, web ADR-0030): the Account's whole
 * data as the multi-sheet backup workbook.
 */
interface BackupApi {

    /** The Backup workbook. It answers the raw .xlsx, not JSON, so Retrofit
     * returns the unparsed response: the bytes come from the body, the dated
     * `budjetame-backup-YYYY-MM-DD.xlsx` name from Content-Disposition. */
    @GET("backup/export")
    suspend fun download(): Response<ResponseBody>
}
