package com.budjetame.android.data.backup

import com.budjetame.android.data.api.BackupApi
import com.budjetame.android.data.api.toApiException
import com.budjetame.android.data.transaction.ExportFile
import com.budjetame.android.data.transaction.exportFilename

/**
 * The backup export operation screens call (ticket #57): downloads the
 * complete multi-sheet backup workbook from the shared backend.
 */
interface BackupGateway {

    /**
     * Fetch the full backup workbook for the signed-in Account. Returns
     * the file and its dated name exactly as the backend produced them
     * (the client never reshapes the workbook).
     */
    suspend fun exportBackup(): ExportFile
}

/**
 * The API-backed BackupGateway (ticket #57): calls GET /backup and maps
 * the raw response to an ExportFile, matching the ledger export's pattern.
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
}