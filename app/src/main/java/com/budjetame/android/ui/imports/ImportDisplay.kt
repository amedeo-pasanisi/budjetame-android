package com.budjetame.android.ui.imports

import com.budjetame.android.data.api.ImportRowStatus
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.ui.transactions.transactionTypeLabel
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Presentation-only logic for the Import screen (ticket #26), ported from
 * the web app's ImportScreen.tsx + importDraft.ts: the status words
 * (Ready/Duplicate/Problem — the Preview's vocabulary over the wire's
 * ok/duplicate/error), the row cards' derived text, the sticky confirm
 * bar's counts and button copy, and the picked-file line. These are the
 * cheap spots where porting bugs hide, so they get direct JVM tests.
 */

/** The wire status's word on the Preview: "Ready" for ok, "Duplicate" for
 * duplicate, "Problem" for error (CONTEXT.md's Preview vocabulary). */
fun importStatusWord(status: ImportRowStatus): String = when (status) {
    ImportRowStatus.OK -> "Ready"
    ImportRowStatus.DUPLICATE -> "Duplicate"
    ImportRowStatus.ERROR -> "Problem"
}

/** A row card's identifying line: "date · Type", with the web's "—" for a
 * field the file did not yield (a parse-error row may have neither). The
 * type word is the Transaction form's own label. */
fun importRowTitleLine(date: String?, type: TransactionType?): String {
    val day = date ?: "—"
    val kind = type?.let(::transactionTypeLabel) ?: "—"
    return "$day · $kind"
}

/** A row card's wallet line: "Source → Destination" for a Transfer with
 * both legs, else the Wallet name — the web row card's subtitle head. */
fun importWalletLine(
    type: TransactionType?,
    wallet: String?,
    sourceWallet: String?,
    destinationWallet: String?,
): String = if (type == TransactionType.TRANSFER &&
    sourceWallet != null && destinationWallet != null
) {
    "$sourceWallet → $destinationWallet"
} else {
    wallet.orEmpty()
}

/** The coordinates suffix of a row card's wallet line: " 📍 lat, lon" when
 * the file carried both coordinates (the web row card), else "". */
fun importLocationSuffix(latitude: String?, longitude: String?): String =
    if (latitude != null && longitude != null) " 📍 $latitude, $longitude" else ""

/** The sticky confirm bar's counts line (web issue #42): "3 ready · 2
 * duplicates · 1 problem", singular words for a count of one. */
fun importCountsText(ready: Int, duplicates: Int, problems: Int): String {
    val duplicateWord = if (duplicates == 1) "duplicate" else "duplicates"
    val problemWord = if (problems == 1) "problem" else "problems"
    return "$ready ready · $duplicates $duplicateWord · $problems $problemWord"
}

/** The confirm bar's Import button label (web issue #42): "Importing…"
 * while the insert is in flight, "Nothing to import" at zero rows, else
 * "Import N row(s)". */
fun importButtonText(confirmable: Int, busy: Boolean): String = when {
    busy -> "Importing…"
    confirmable == 0 -> "Nothing to import"
    else -> "Import $confirmable row${if (confirmable == 1) "" else "s"}"
}

/** The pick phase's file line: "name · N KB", with the web's floor of 1 KB
 * (a 0-byte file still reads "1 KB"). */
fun pickedFileLine(name: String, sizeBytes: Long): String =
    "$name · ${max(1, (sizeBytes / 1024.0).roundToInt())} KB"
