package com.budjetame.android.data.api

import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Backup resource (ticket #57, web #112, web ADR-0030): the Account's whole
 * data as the multi-sheet backup workbook, and the Restore from it (ticket
 * #60, web #115).
 */
interface BackupApi {

    /** The Backup workbook. It answers the raw .xlsx, not JSON, so Retrofit
     * returns the unparsed response: the bytes come from the body, the dated
     * `budjetame-backup-YYYY-MM-DD.xlsx` name from Content-Disposition. */
    @GET("backup/export")
    suspend fun download(): Response<ResponseBody>

    /** Restore the Account's data from an uploaded Backup workbook. The
     * server validates the whole file, then atomically replaces every
     * Transaction, Wallet, Category, Recurring definition and skip; a
     * malformed file answers 422 with its `detail` and changes nothing. */
    @Multipart
    @POST("backup/restore")
    suspend fun restore(@Part file: MultipartBody.Part): RestoreResponseDto
}

/** The restore's answer: `status` is always "ok"; `warning` names the
 * origin mismatch when the Backup was exported from another Account. */
@Serializable
data class RestoreResponseDto(
    val status: String,
    val warning: String? = null,
)
