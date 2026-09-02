package com.budjetame.android.data.transaction

/**
 * The export's payload (US 7.3, ticket #28): the server-produced .xlsx
 * bytes and the filename the server attached — the dated
 * `budjetame-YYYY-MM-DD.xlsx` name decided in Europe/Rome on the backend —
 * so the client never guesses the file's name or the day. The bytes are
 * the import template's workbook exactly as the backend wrote them: the
 * client performs no transformation on the file.
 */
data class ExportFile(
    val filename: String,
    val content: ByteArray,
)

/**
 * The filename from the response's Content-Disposition, or the fallback
 * when the header is missing or carries no quoted filename (ported from
 * the web transport's `exportFilename`). The server always attaches
 * `attachment; filename="budjetame-YYYY-MM-DD.xlsx"`; the fallback keeps
 * the share flow working against a proxy that stripped the header.
 */
internal fun exportFilename(disposition: String?): String {
    val match = disposition?.let { FILENAME_PATTERN.find(it) }
    return match?.groupValues?.get(1) ?: FALLBACK_EXPORT_FILENAME
}

private val FILENAME_PATTERN = Regex("""filename="([^"]+)"""")

internal const val FALLBACK_EXPORT_FILENAME = "budjetame-export.xlsx"
