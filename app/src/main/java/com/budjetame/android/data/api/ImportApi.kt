package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * One import Preview row's verdict (T13): "ok" (Ready in the UI — nothing
 * written yet), "duplicate" (a row already in the database or repeated in
 * this file), or "error" (a parse or rule failure, detailed in `error`).
 */
@Serializable
enum class ImportRowStatus {
    @SerialName("ok") OK,
    @SerialName("duplicate") DUPLICATE,
    @SerialName("error") ERROR,
}

/**
 * One row of an import preview (T13): the template's extracted fields —
 * names, not ids: the backend re-resolves them — plus the pipeline's
 * verdict. `row` is the file's line number (the header is line 1), which
 * the Draft's selection and every later call key on. Fields that failed to
 * parse are null; a blank description arrives as null (a blank description
 * matches a missing one, ADR-0006).
 */
@Serializable
data class ImportRowDto(
    val row: Int,
    val status: ImportRowStatus = ImportRowStatus.OK,
    val type: TransactionType? = null,
    val date: String? = null,
    val amount: String? = null,
    val wallet: String? = null,
    val source_wallet: String? = null,
    val destination_wallet: String? = null,
    val category: String? = null,
    val description: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
    val error: String? = null,
)

/** The validated extract of an uploaded file (T13): every row with its
 * verdict, and the counts. Nothing is inserted by this step. */
@Serializable
data class ImportPreviewDto(
    val rows: List<ImportRowDto>,
    val ok_count: Int,
    val error_count: Int,
    val duplicate_count: Int,
)

/**
 * A row the user kept, echoed back for confirmation (T13): the template's
 * fields as names, not ids — the backend re-resolves them and re-runs every
 * rule before writing anything. Blank fields travel as null (a blank
 * description matches a missing one, ADR-0006); an Expense/Income fills
 * `wallet`/`category`, a Transfer fills the two legs and never carries a
 * Category, so at most one of the two shapes is ever set. Null fields are
 * omitted from the wire body (the converter skips default-valued fields);
 * the backend's own defaults treat them as absent.
 */
@Serializable
data class ImportRowInput(
    val row: Int,
    val type: TransactionType,
    val amount: String,
    val date: String,
    val wallet: String? = null,
    val source_wallet: String? = null,
    val destination_wallet: String? = null,
    val category: String? = null,
    val description: String? = null,
    val latitude: String? = null,
    val longitude: String? = null,
)

/** One edited Preview row to re-validate during Verification (issue #44):
 * `row` carries the row's edited fields; `earlier_rows` the Draft's rows
 * that precede it in the file — the in-file half of the Duplicate check —
 * which the endpoint cannot see by itself. Nothing is written. */
@Serializable
data class ImportRowValidationRequest(
    val row: ImportRowInput,
    val earlier_rows: List<ImportRowInput> = emptyList(),
)

/** The fresh verdict for one edited row (issue #44): `status` speaks the
 * Preview's vocabulary — ok (Ready in the UI), duplicate, or error — and
 * `error` carries the message for an error row. */
@Serializable
data class ImportRowValidationDto(
    val status: ImportRowStatus,
    val error: String? = null,
)

/** A batch Revalidation (web issue #76): the Draft's rows — with the user's
 * edits applied — plus the target row numbers to re-validate, in one call.
 * `rows` is the whole Draft in file order (the preceding rows are the
 * in-file Duplicate context, which the endpoint cannot see by itself);
 * `targets` names the rows — by their `row` number, the file's line —
 * whose fresh verdicts are wanted. Nothing is written. */
@Serializable
data class ImportBatchRevalidationRequest(
    val rows: List<ImportRowInput>,
    val targets: List<Int>,
)

/** The fresh verdict for one target row of a batch Revalidation (web issue
 * #76): `row` echoes the target's row number so the client can map each
 * verdict back to its Draft row; `status` and `error` speak the Preview's
 * vocabulary, exactly like the single-row re-validation. */
@Serializable
data class ImportRowRevalidationDto(
    val row: Int,
    val status: ImportRowStatus,
    val error: String? = null,
)

/** The rows the user confirmed (T13): the subset of the Preview they kept.
 * The insert is transactional — any invalid row rejects the whole batch. */
@Serializable
data class ImportConfirmRequest(
    val rows: List<ImportRowInput>,
)

/**
 * Imports resource (T13): upload a .csv/.xlsx file against the fixed
 * template and preview its rows (nothing written), re-validate one edited
 * row during Verification (issue #44) or a batch of problem rows (web
 * issue #76), and confirm the kept rows — the only step that writes,
 * transactionally (201 with the created Transactions, 422 rejecting the
 * whole batch when any row is invalid or now-duplicate). The computation
 * endpoints never bump the data version (ADR-0002); confirm is a real
 * write and does.
 */
interface ImportApi {

    /** 422 on an empty file, a non-.csv/.xlsx upload, or a missing required
     * template column, with the backend's detail as the message. */
    @Multipart
    @POST("import/preview")
    suspend fun preview(@Part file: MultipartBody.Part): ImportPreviewDto

    @POST("import/validate-row")
    suspend fun validateRow(@Body body: ImportRowValidationRequest): ImportRowValidationDto

    /** Batch Revalidation (web issue #76): the whole Draft as the in-file
     * Duplicate context plus the target row numbers in, every target's
     * fresh verdict out — one call through the same resolution and rules
     * as the Preview and the single-row re-validation. Nothing is
     * written; exempt from the data-version bump like the other
     * computation endpoints (ADR-0002). */
    @POST("import/revalidate-rows")
    suspend fun revalidateRows(@Body body: ImportBatchRevalidationRequest): List<ImportRowRevalidationDto>

    /** 201 with the created Transactions (each carrying the Cash
     * negative-balance `warning`); 422 rejects the whole batch. */
    @POST("import/confirm")
    suspend fun confirm(@Body body: ImportConfirmRequest): List<TransactionDto>
}
