package com.budjetame.android.data.backup

import com.budjetame.android.data.api.BackupApi
import com.budjetame.android.data.api.toApiException
import com.budjetame.android.data.transaction.exportFilename

/**
 * A downloaded Backup (CONTEXT.md: Backup): the workbook's bytes exactly as
 * the server wrote them, and the dated filename the server attached, so the
 * client never guesses the file's name or the day.
 */
class BackupFile(
    val filename: String,
    val content: ByteArray,
)

/** The Backup operations the Settings dialog calls. */
interface BackupGateway {
    /** Download the Account's complete Backup workbook. Throws an
     * ApiException when the server refuses it. */
    suspend fun exportAll(): BackupFile
}

/** The API-backed BackupGateway. */
class ApiBackupRepository(private val api: BackupApi) : BackupGateway {

    override suspend fun exportAll(): BackupFile {
        // A raw-body endpoint: Retrofit does not throw for a non-2xx, so the
        // response is checked here, with the same detail parsing.
        val response = api.export()
        if (!response.isSuccessful) throw response.toApiException()
        return BackupFile(
            filename = exportFilename(response.headers()["Content-Disposition"], FALLBACK_BACKUP_FILENAME),
            content = response.body()?.bytes() ?: ByteArray(0),
        )
    }
}

/** The web transport's fallback when the header is missing. */
private const val FALLBACK_BACKUP_FILENAME = "budjetame-backup.xlsx"
