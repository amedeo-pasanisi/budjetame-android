package com.budjetame.android.data.backup

import com.budjetame.android.data.api.BackupApi
import com.budjetame.android.data.api.toApiException
import com.budjetame.android.data.transaction.exportFilename
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/**
 * A downloaded Backup (CONTEXT.md: Backup): the workbook's bytes exactly as
 * the server wrote them, and the dated filename the server attached, so the
 * client never guesses the file's name or the day.
 */
class BackupFile(
    val filename: String,
    val content: ByteArray,
)

/** A completed restore: [warning] is the server's origin-mismatch notice
 * ("Backup was exported from …, not from …"), null when the Backup came from
 * the signed-in Account. */
data class RestoreResult(val warning: String?)

/** The Backup operations the Settings dialog calls. */
interface BackupGateway {
    /** Download the Account's complete Backup workbook. Throws when the
     * server refuses it or sends no file. */
    suspend fun download(): BackupFile

    /** Replace all the Account's data with the picked Backup workbook's
     * contents. Throws an ApiException carrying the server's `detail` when
     * the file is refused (422), in which case nothing changed. */
    suspend fun restore(filename: String, content: ByteArray): RestoreResult
}

/** The API-backed BackupGateway. */
class ApiBackupRepository(private val api: BackupApi) : BackupGateway {

    override suspend fun download(): BackupFile {
        // A raw-body endpoint: Retrofit does not throw for a non-2xx, so the
        // response is checked here, with the same detail parsing.
        val response = api.download()
        if (!response.isSuccessful) throw response.toApiException()
        val content = response.body()?.bytes()
        // An empty answer is no Backup: nothing must be saved from it.
        check(content != null && content.isNotEmpty()) { "The server sent no backup workbook." }
        return BackupFile(
            filename = exportFilename(response.headers()["Content-Disposition"], FALLBACK_BACKUP_FILENAME),
            content = content,
        )
    }

    override suspend fun restore(filename: String, content: ByteArray): RestoreResult = try {
        val response = api.restore(
            // The server reads the workbook's bytes; the part's name and
            // content type are immaterial to it.
            MultipartBody.Part.createFormData("file", filename, content.toRequestBody(OCTET_STREAM)),
        )
        RestoreResult(warning = response.warning)
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}

private val OCTET_STREAM = "application/octet-stream".toMediaType()

/** The web transport's fallback when the header is missing. */
private const val FALLBACK_BACKUP_FILENAME = "budjetame-backup.xlsx"
