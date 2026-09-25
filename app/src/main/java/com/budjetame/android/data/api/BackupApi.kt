package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * The result of a restore (issue #60): a successful restore replaces all
 * Account data atomically. `origin_marker_warning` is true when the
 * uploaded workbook's origin marker doesn't match the Account's current
 * marker — the restore still succeeded, but the mismatch is surfaced as a
 * warning.
 */
@Serializable
data class RestoreResultDto(
    val ok: Boolean = true,
    val origin_marker_warning: Boolean = false,
)

/**
 * Backup resource (ticket #57): the complete multi-sheet backup workbook
 * — every Account entity (Accounts + Opening Balances, Wallets, Categories,
 * Transactions, Recurring Costs/Incomes, Skips) — as a raw .xlsx download.
 * The response carries the dated filename in Content-Disposition, exactly
 * like the ledger export. Also handles restore (issue #60): POST the file
 * bytes to atomically replace all Account data.
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

    /**
     * Atomically replace all Account data from an uploaded backup
     * workbook. A malformed or corrupt file is rejected with 422 and
     * leaves the current data untouched (fail-closed). A file whose
     * origin marker doesn't matches answers 200 with
     * `origin_marker_warning: true`.
     *
     * 200 with RestoreResultDto on success (origin_marker_warning
     * set in the response body when the markers don't match).
     * 422 when the file is malformed — nothing changed.
     */
    @Multipart
    @POST("backup/restore")
    suspend fun restore(@Part file: MultipartBody.Part): RestoreResultDto
}