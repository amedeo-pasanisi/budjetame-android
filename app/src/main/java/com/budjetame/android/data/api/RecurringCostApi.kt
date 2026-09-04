package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * The interval units a Recurring Cost repeats on (CONTEXT.md); the wire
 * values match the backend's enum.
 */
@Serializable
enum class IntervalUnit {
    @SerialName("days") DAYS,
    @SerialName("weeks") WEEKS,
    @SerialName("months") MONTHS,
    @SerialName("years") YEARS,
}

/**
 * The two fields the Recurring screen's one order reads — next due date
 * ascending, ties by name (the web app's generic `sortByNextDue`, shared by
 * the Costs and the Incomes sides). Both definition DTOs implement it so the
 * one ordering function serves the two sides (ADR-0011 leaves the display
 * layer free to share pure logic).
 */
interface RecurringDefinition {
    val next_due_date: String
    val name: String
}

/**
 * One row of the Occurrences section (web ADR-0026): a non-Paid
 * Occurrence — its own date (ADR-0024) and whether the user excused it
 * (Skipped, ADR-0016). Paid history lives in the ledger and never appears.
 * One DTO serves both the Recurring Cost and the Recurring Income reads
 * (ADR-0011): the two sides' occurrences are the same shape. The wire
 * values match the backend's.
 */
@Serializable
data class RecurringOccurrenceDto(
    val date: String,
    val skipped: Boolean,
)

/**
 * The per-Occurrence skip write's body (web ADR-0026): PUT the
 * Occurrence's date with `{"skipped": true}` to excuse it, `false` to
 * restore it. The write is idempotent — stating the current state changes
 * nothing — and the response is the refreshed read.
 */
@Serializable
data class RecurringOccurrenceUpdateRequest(
    val skipped: Boolean,
)

/**
 * A Recurring Cost as seen through the API (web issue #56): the editable
 * definition (name, amount, interval, start date) plus the derived state —
 * never stored, computed on the backend from the definition and the stored
 * link pins. `next_due_date` is the next Occurrence's own date — its due
 * date (ADR-0024) — on or after today in Europe/Rome;
 * `next_unpaid_occurrence_date` is the Occurrence a new linked Expense
 * would pay — the oldest Unpaid one's own date (web issue #57), what the
 * transaction form's picker shows; `backlog_count` is the Backlog (web
 * issue #58) — Unpaid Occurrences due today or earlier, the red "N
 * unpaid" badge (web ADR-0025 / ticket #45). The backend's derived
 * `overdue` field is no longer sent — the badge is the one Backlog
 * signal — so the DTO dropped it; a backend that still sends the field
 * parses cleanly (the JSON config ignores unknown keys). The card
 * Skip/Un-skip button is gone (web ADR-0026): skip controls live per
 * Occurrence on the Occurrences read, never on the definition, so
 * `next_skip_action` left the DTO — a backend that still sends it parses
 * cleanly the same way.
 * `start_date` is the stored start date — every definition always carries
 * one (ADR-0024): left empty at creation it is set to the creation day,
 * and an Occurrence's due date is its own date, so the optional due-date
 * override is gone.
 */
@Serializable
data class RecurringCostDto(
    val id: Int,
    override val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String,
    override val next_due_date: String,
    val next_unpaid_occurrence_date: String,
    val backlog_count: Int = 0,
    val created_at: String,
) : RecurringDefinition

/**
 * Create a Recurring Cost: name (unique per Account, case-insensitively),
 * amount (a string on the wire, like every amount), the repetition — every
 * N days/weeks/months/years — and an optional start date (ADR-0024): left
 * empty at creation it is set to the creation day, so every definition
 * always carries one. A null `start_date` is omitted from the wire body
 * (the converter skips default-valued fields) — "start today" needs no
 * typing.
 */
@Serializable
data class RecurringCostCreateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String? = null,
)

/**
 * Edit a Recurring Cost: the form always sends the whole definition — every
 * field present (the converter's `explicitNulls`) — under the
 * TransactionUpdate contract (a field present is applied even when null).
 * `start_date` is the one exception to the null-clears rule and is
 * non-nullable here by type: a definition always carries a start date
 * (ADR-0024), it can be changed, never unset, and the backend answers 422
 * on an explicit null. The backend also answers 409 on a duplicate name.
 */
@Serializable
data class RecurringCostUpdateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String,
)

/**
 * Recurring Costs resource (web issue #56): the list, sorted by next due
 * date ascending (ties by name) — the one order the Recurring screen needs —
 * the create/edit/delete writes, and the Occurrences read and its
 * per-Occurrence skip write (web ADR-0026) — the card Skip/Un-skip
 * toggle is gone with the endpoint it pressed. A delete severs the links
 * and drops its skips (CONTEXT.md, ADR-0016): linked Expenses stay as
 * ordinary Expenses.
 */
interface RecurringCostApi {

    @GET("recurring-costs")
    suspend fun list(): List<RecurringCostDto>

    /** 201 with the created Recurring Cost; 409 duplicate name; 422 on a
     * start date that is not a Europe/Rome calendar day. */
    @POST("recurring-costs")
    suspend fun create(@Body body: RecurringCostCreateRequest): RecurringCostDto

    /** 409 duplicate name; 422 on an invalid start date or an explicit
     * null one — a definition always carries its start date (ADR-0024). */
    @PATCH("recurring-costs/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: RecurringCostUpdateRequest): RecurringCostDto

    /** 204: the definition is gone, its links severed, its skips dropped. */
    @DELETE("recurring-costs/{id}")
    suspend fun delete(@Path("id") id: Int)

    /** The Occurrences section's read (web ADR-0026): every non-Paid
     * Occurrence with its skipped state — newest first, the one order the
     * edit modal renders: the next incoming Unpaid row on top, then every
     * excused future row, then the past rows (today first) down to the
     * oldest. The response is the section's whole state: each row carries
     * exactly what its Skip/Un-skip button needs. Foreign ids answer 403.
     * The order is server-computed and authoritative — a client renders
     * the list verbatim and never re-sorts it. */
    @GET("recurring-costs/{id}/occurrences")
    suspend fun occurrences(@Path("id") id: Int): List<RecurringOccurrenceDto>

    /** The per-Occurrence skip write (web ADR-0026): state the row's
     * skipped state — skip or un-skip — idempotently (a double tap cannot
     * double-flip). 422 on a Paid Occurrence or a date that is not one of
     * the definition's Occurrences. The answer is the refreshed read, so
     * the modal swaps its rows in without a second fetch. Foreign ids
     * answer 403. */
    @PUT("recurring-costs/{id}/occurrences/{occurrenceDate}")
    suspend fun setOccurrenceSkipped(
        @Path("id") id: Int,
        @Path("occurrenceDate") occurrenceDate: String,
        @Body body: RecurringOccurrenceUpdateRequest,
    ): List<RecurringOccurrenceDto>
}
