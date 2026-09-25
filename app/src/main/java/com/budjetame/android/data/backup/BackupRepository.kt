package com.budjetame.android.data.backup

import com.budjetame.android.data.api.BackupApi
import com.budjetame.android.data.api.RestoreResultDto
import com.budjetame.android.data.api.toApiException
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.exportFilename
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * The backup export operation screens call (ticket #57): downloads the
 * complete multi-sheet backup workbook from the shared backend. Also
 * handles restore (issue #60): posts a backup workbook to atomically
 * replace all Account data.
 */
interface BackupGateway {

    /**
     * Fetch the full backup workbook for the signed-in Account. Returns
     * the file and its dated name exactly as the backend produced them
     * (the client never reshapes the workbook).
     */
    suspend fun exportBackup(): ExportFile

    /**
     * Restore Account data from a backup workbook (issue #60). The backup
     * bytes and filename come from the SAF file picker. Returns the
     * restore result — success with optional origin-marker warning.
     * A malformed file throws [com.budjetame.android.data.api.ApiException]
     * with status 422 and the backend's detail.
     */
    suspend fun restoreBackup(fileName: String, content: ByteArray): RestoreResultDto
}

/**
 * The API-backed BackupGateway (ticket #57): calls GET /backup and maps
 * the raw response to an ExportFile, matching the ledger export's pattern.
 * Also calls POST /backup/restore (issue #60) with a multipart upload.
 */
class ApiBackupRepository(private val api: BackupApi) : BackupGateway {

    override suspend fun exportBackup(): ExportFile {
        // A raw-body endpoint: Retrofit does not throw for a non-2xx (the
        // body is .xlsx, not JSON), so the mapping checks the response and
        // reuses the same detail parsing as the HttpException mapping.
        val response = api.export()
        if (!response.isSuccessful) throw response.toApiException()
        return ExportFile(
            filename = exportFilename(response.headers()["Content-Disposition"]),
            content = response.body()?.bytes() ?: ByteArray(0),
        )
    }

    override suspend fun restoreBackup(fileName: String, content: ByteArray): RestoreResultDto = call {
        val part = MultipartBody.Part.createFormData(
            "file",
            fileName,
            content.toRequestBody(OCTET_STREAM),
        )
        api.restore(part)
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }

    companion object {
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}