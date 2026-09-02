package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
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
 * What the Skip/Un-skip button reads (ADR-0016 in the web repo): "skip"
 * while the press would excuse the oldest Unpaid, un-Skipped Occurrence —
 * the front of the queue, the very one a new link would pay — and "unskip"
 * once the whole Backlog is excused, when the press restores the oldest
 * Skipped one. One enum serves both definition DTOs (ADR-0011); the wire
 * values match the backend's.
 */
@Serializable
enum class SkipAction {
    @SerialName("skip") SKIP,
    @SerialName("unskip") UNSKIP,
}

/**
 * A Recurring Cost as seen through the API (web issue #56): the editable
 * definition (name, amount, interval, optional start date, optional due-date
 * override) plus the derived state — never stored, computed on the backend
 * from the definition and the stored link pins. `next_due_date` is the next
 * Occurrence's due date (override applied, clamping included) on or after
 * today in Europe/Rome; `next_unpaid_occurrence_date` is the Occurrence a
 * new linked Expense would pay — the oldest Unpaid one's own date (web issue
 * #57), what the transaction form's picker shows; `backlog_count` is the
 * Backlog (web issue #58) — Unpaid Occurrences due today or earlier, the "N
 * unpaid" badge; `overdue` is true exactly when the Backlog is non-empty.
 * `start_date` is the stored value — null when unset, meaning the creation
 * date. `next_skip_action` is what the Skip/Un-skip button reads
 * (ADR-0016): "skip" while an Unpaid, un-Skipped Occurrence is the front
 * of the queue, "unskip" once the press would restore the oldest Skipped
 * one — Skipped Occurrences never enter the Backlog count and never show
 * as the next due or the next Unpaid Occurrence (the backend derives it
 * all).
 */
@Serializable
data class RecurringCostDto(
    val id: Int,
    override val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String? = null,
    val due_day: Int? = null,
    val due_month: Int? = null,
    override val next_due_date: String,
    val next_unpaid_occurrence_date: String,
    val backlog_count: Int = 0,
    val overdue: Boolean = false,
    val next_skip_action: SkipAction = SkipAction.SKIP,
    val created_at: String,
) : RecurringDefinition

/**
 * Create a Recurring Cost: name (unique per Account, case-insensitively),
 * amount (a string on the wire, like every amount), the repetition — every
 * N days/weeks/months/years — an optional start date (unset defaults to the
 * creation date), and the optional due-date override whose shape follows the
 * interval unit (a day-of-month for months, a month+day for years, never for
 * days/weeks). Null fields are omitted from the wire body (the converter
 * skips default-valued fields).
 */
@Serializable
data class RecurringCostCreateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String? = null,
    val due_day: Int? = null,
    val due_month: Int? = null,
)

/**
 * Edit a Recurring Cost: the form always sends the whole definition — every
 * field present, nulls included (the converter's `explicitNulls`) — so
 * clearing the optional start date or override travels as an explicit null,
 * the TransactionUpdate contract (a field present is applied even when
 * null). The backend re-validates the resulting override against the stored
 * definition and answers 409 on a duplicate name.
 */
@Serializable
data class RecurringCostUpdateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String?,
    val due_day: Int?,
    val due_month: Int?,
)

/**
 * Recurring Costs resource (web issue #56): the list, sorted by next due
 * date ascending (ties by name) — the one order the Recurring screen needs —
 * and the create/edit/delete writes and the Skip/Un-skip toggle. A delete
 * severs the links and drops its skips (CONTEXT.md, ADR-0016): linked
 * Expenses stay as ordinary Expenses.
 */
interface RecurringCostApi {

    @GET("recurring-costs")
    suspend fun list(): List<RecurringCostDto>

    /** 201 with the created Recurring Cost; 409 duplicate name; 422 on an
     * override that does not fit the interval unit. */
    @POST("recurring-costs")
    suspend fun create(@Body body: RecurringCostCreateRequest): RecurringCostDto

    /** 409 duplicate name; 422 on an override that does not fit the unit. */
    @PATCH("recurring-costs/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: RecurringCostUpdateRequest): RecurringCostDto

    /** 204: the definition is gone, its links severed, its skips dropped. */
    @DELETE("recurring-costs/{id}")
    suspend fun delete(@Path("id") id: Int)

    /** The Skip/Un-skip button (ADR-0016): the backend flips the front of
     * the queue — it skips the oldest Unpaid, un-Skipped Occurrence or,
     * once the whole Backlog is excused, un-skips the oldest Skipped one —
     * and answers 200 with the refreshed definition, every derived field
     * re-derived from the stored skips. Foreign ids answer 403. */
    @POST("recurring-costs/{id}/skip-toggle")
    suspend fun skipToggle(@Path("id") id: Int): RecurringCostDto
}
