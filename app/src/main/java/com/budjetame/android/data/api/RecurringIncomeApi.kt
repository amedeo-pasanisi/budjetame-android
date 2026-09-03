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
 * interval, start date) plus the derived state — never stored, computed on
 * the backend from the definition and the stored link pins.
 * `next_due_date` is the next Occurrence's own date — its due date
 * (ADR-0024) — on or after today in Europe/Rome;
 * `next_unpaid_occurrence_date` is the Occurrence a new linked Income
 * would pay — the oldest Unpaid one's own date (web issue #61), what the
 * transaction form's picker shows; `backlog_count` is the Backlog (web
 * issue #62) — Unpaid Occurrences due today or earlier, the "N unpaid"
 * badge; `overdue` is true exactly when the Backlog is non-empty.
 * `start_date` is the stored start date — every definition always carries
 * one (ADR-0024): left empty at creation it is set to the creation day,
 * and an Occurrence's due date is its own date, so the optional due-date
 * override is gone. `next_skip_action` is what the Skip/Un-skip button
 * reads (ADR-0016): "skip" while an Unpaid, un-Skipped Occurrence is the
 * front of the queue, "unskip" once the press would restore the oldest
 * Skipped one — Skipped Occurrences never enter the Backlog count and
 * never show as the next due or the next Unpaid Occurrence (the backend
 * derives it all).
 */
@Serializable
data class RecurringIncomeDto(
    val id: Int,
    override val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String,
    override val next_due_date: String,
    val next_unpaid_occurrence_date: String,
    val backlog_count: Int = 0,
    val overdue: Boolean = false,
    val next_skip_action: SkipAction = SkipAction.SKIP,
    val created_at: String,
) : RecurringDefinition

/**
 * Create a Recurring Income (web issue #60): name (unique per Account,
 * case-insensitively), amount (a string on the wire, like every amount),
 * the repetition — every N days/weeks/months/years — and an optional
 * start date (ADR-0024): left empty at creation it is set to the creation
 * day, so every definition always carries one. A null `start_date` is
 * omitted from the wire body (the converter skips default-valued fields) —
 * "start today" needs no typing.
 */
@Serializable
data class RecurringIncomeCreateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String? = null,
)

/**
 * Edit a Recurring Income: the form always sends the whole definition —
 * every field present (the converter's `explicitNulls`) — under the
 * TransactionUpdate contract (a field present is applied even when null).
 * `start_date` is the one exception to the null-clears rule and is
 * non-nullable here by type: a definition always carries a start date
 * (ADR-0024), it can be changed, never unset, and the backend answers 422
 * on an explicit null. The backend also answers 409 on a duplicate name.
 */
@Serializable
data class RecurringIncomeUpdateRequest(
    val name: String,
    val amount: String,
    val interval_value: Int,
    val interval_unit: IntervalUnit,
    val start_date: String,
)

/**
 * Recurring Incomes resource (web issue #60): the list, sorted by next due
 * date ascending (ties by name) — the one order the Recurring screen needs —
 * and the create/edit/delete writes and the Skip/Un-skip toggle. A delete
 * severs the links and drops its skips (CONTEXT.md, ADR-0016): linked
 * Incomes stay as ordinary Incomes.
 */
interface RecurringIncomeApi {

    @GET("recurring-incomes")
    suspend fun list(): List<RecurringIncomeDto>

    /** 201 with the created Recurring Income; 409 duplicate name; 422 on a
     * start date that is not a Europe/Rome calendar day. */
    @POST("recurring-incomes")
    suspend fun create(@Body body: RecurringIncomeCreateRequest): RecurringIncomeDto

    /** 409 duplicate name; 422 on an invalid start date or an explicit
     * null one — a definition always carries its start date (ADR-0024). */
    @PATCH("recurring-incomes/{id}")
    suspend fun update(@Path("id") id: Int, @Body body: RecurringIncomeUpdateRequest): RecurringIncomeDto

    /** 204: the definition is gone, its links severed, its skips dropped. */
    @DELETE("recurring-incomes/{id}")
    suspend fun delete(@Path("id") id: Int)

    /** The Skip/Un-skip button (ADR-0016), the mirror of the Costs side:
     * the backend flips the front of the queue — it skips the oldest
     * Unpaid, un-Skipped Occurrence or, once the whole Backlog is excused,
     * un-skips the oldest Skipped one — and answers 200 with the refreshed
     * definition, every derived field re-derived from the stored skips.
     * Foreign ids answer 403. */
    @POST("recurring-incomes/{id}/skip-toggle")
    suspend fun skipToggle(@Path("id") id: Int): RecurringIncomeDto
}
