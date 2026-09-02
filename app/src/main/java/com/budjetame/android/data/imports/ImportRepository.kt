package com.budjetame.android.data.imports

import com.budjetame.android.data.api.ImportApi
import com.budjetame.android.data.api.ImportConfirmRequest
import com.budjetame.android.data.api.ImportPreviewDto
import com.budjetame.android.data.api.ImportRowInput
import com.budjetame.android.data.api.ImportRowValidationDto
import com.budjetame.android.data.api.ImportRowValidationRequest
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.toApiException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * The import operations screens call (UI-independent): upload-and-preview,
 * the single-row re-validation of Verification (issue #44), and the
 * transactional confirm (T13). Nothing is written by the first two; the
 * computation endpoints are exempt from the data-version bump (ADR-0002),
 * confirm is a real write and bumps like any other.
 */
interface ImportGateway {

    /** Upload the picked .csv/.xlsx file and get every row's verdict with
     * the counts (ok/duplicate/error). Nothing is written. */
    suspend fun preview(fileName: String, content: ByteArray): ImportPreviewDto

    /** Re-validate one edited row during Verification: the fresh verdict
     * for the row as edited, with `earlierRows` — the Draft's preceding
     * rows, edits applied — as the in-file Duplicate context. Nothing is
     * written. */
    suspend fun validateRow(
        row: ImportRowInput,
        earlierRows: List<ImportRowInput>,
    ): ImportRowValidationDto

    /** Insert the confirmed rows transactionally; the response is the
     * created Transactions, each carrying the Cash negative-balance
     * warning flag. */
    suspend fun confirm(rows: List<ImportRowInput>): List<TransactionDto>
}

/** The API-backed ImportGateway. */
class ApiImportRepository(private val api: ImportApi) : ImportGateway {

    override suspend fun preview(fileName: String, content: ByteArray): ImportPreviewDto =
        call {
            api.preview(
                // The template's parsing keys on the file name's extension,
                // so the part's content type is immaterial.
                MultipartBody.Part.createFormData(
                    "file",
                    fileName,
                    content.toRequestBody(OCTET_STREAM),
                ),
            )
        }

    override suspend fun validateRow(
        row: ImportRowInput,
        earlierRows: List<ImportRowInput>,
    ): ImportRowValidationDto = call {
        api.validateRow(ImportRowValidationRequest(row = row, earlier_rows = earlierRows))
    }

    override suspend fun confirm(rows: List<ImportRowInput>): List<TransactionDto> =
        call { api.confirm(ImportConfirmRequest(rows = rows)) }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
