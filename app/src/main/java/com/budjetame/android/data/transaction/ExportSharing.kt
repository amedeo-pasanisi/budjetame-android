package com.budjetame.android.data.transaction

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Write the exported workbook to the app's cache under the name the
 * backend chose and return its FileProvider Uri for the share sheet, or
 * null when the write failed. Shared by the ledger export and the
 * full backup export (ticket #57). The cache lives in the app's private
 * storage; the provider grants the receiving app one read.
 */
fun cacheExportFile(context: Context, export: ExportFile): Uri? = try {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(directory, export.filename)
    file.writeBytes(export.content)
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
} catch (_: Exception) {
    null
}

/** The .xlsx content type: the export's MIME, like the import picker's
 * accepted set and the backup export (ticket #57). */
const val EXPORT_MIME_TYPE =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/**
 * Share an exported file via the system share sheet (ticket #57): cache
 * the bytes, then fire ACTION_SEND with the FileProvider URI so the user
 * can save/email/share to their preferred location. Returns null when
 * everything succeeded, or an error message string when caching or
 * sharing failed.
 */
fun shareExportFile(context: Context, export: ExportFile): String? {
    val uri = cacheExportFile(context, export) ?: return "Could not save the export file."
    val send = Intent(Intent.ACTION_SEND).apply {
        type = EXPORT_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return try {
        context.startActivity(Intent.createChooser(send, null))
        null
    } catch (_: Exception) {
        "Could not share the export file."
    }
}