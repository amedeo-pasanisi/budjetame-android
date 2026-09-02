package com.budjetame.android.data.api

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * A Recurring Income as seen through the API (web issue #60), mirroring
 * RecurringCostDto (ADR-0011): the editable definition (name, amount,
 * interval, optional start date, optional due-date override) plus the
 * derived state — never stored, computed on the backend from the definition
 * and the stored link pins. `next_due_date` is the next Occurrence's due
 * date (override applied, clamping included) on or after today in
 * Europe/Rome; `next_unpaid_occurrence_date` is the Occurrence a new linked
 * Income would pay — the oldest Unpaid one's own date (web issue #61), what
 * the transaction form's picker shows; `backlog_count` is the Backlog (web
 * issue #62) — Unpaid Occurrences due today or earlier, the "N unpaid"
 * badge; `overdue` is true exactly when the Backlog is non-empty.
 * `start_date` is the stored value — null when unset, meaning the creation
 * date. The backend also sends `next_skip_action` (ADR-0016, a later
 * ticket); this client ignores it until the skip feature lands.
 */
@Serializable
data class RecurringIncomeDto(
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
    val created_at: String,
) : RecurringDefinition

/**
 * Create a Recurring Income (web issue #60): name (unique per Account,
 * case-insensitively), amount (a string on the wire, like every amount),
 * the repetition — every N days/weeks/months/years — an optional start date
 * (unset defaults to the creation date), and the optional due-date override
 * whose shape follows the interval unit (a day-of-month for months, a
 * month+day for years, never for days/weeks). Null fields are omitted from
 * the wire body (the converter skips default-valued fields).
 */
@Serializable
data class RecurringIncomeCreateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String? = null,
    val due_day: Int? = null,
    val due_month: Int? = null,
)

/**
 * Edit a Recurring Income: the form always sends the whole definition —
 * every field present, nulls included (the converter's `explicitNulls`) —
 * so clearing the optional start date or override travels as an explicit
 * null, the TransactionUpdate contract (a field present is applied even
 * when null). The backend re-validates the resulting override against the
 * stored definition and answers 409 on a duplicate name.
 */
@Serializable
data class RecurringIncomeUpdateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String?,
    val due_day: Int?,
    val due_month: Int?,
)

/**
 * Recurring Incomes resource (web issue #60): the list, sorted by next due
 * date ascending (ties by name) — the one order the Recurring screen needs —
 * and the create/edit/delete writes. A delete severs the links (CONTEXT.md):
 * linked Incomes stay as ordinary Incomes.
 */
interface RecurringIncomeApi {

    @GET("recurring-incomes")
    suspend fun list(): List<RecurringIncomeDto>

    /** 201 with the created Recurring Income; 409 duplicate name; 422 on an
     * override that does not fit the interval unit. */
    @POST("recurring-incomes")
    suspend fun create(@Body body: RecurringIncomeCreateRequest): RecurringIncomeDto

    /** 409 duplicate name; 422 on an override that does not fit the unit. */
    @PATCH("recurring-incomes/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: RecurringIncomeUpdateRequest): RecurringIncomeDto

    /** 204: the definition is gone and its links are severed. */
    @DELETE("recurring-incomes/{id}")
    suspend fun delete(@Path("id") id: Int)
}
