package com.budjetame.android.data.recurringcost

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringCostCreateRequest
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringCostUpdateRequest
import com.budjetame.android.data.api.RecurringOccurrenceDto
import com.budjetame.android.data.api.RecurringOccurrenceUpdateRequest
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/**
 * The fields the Recurring Cost form drafts (UI-independent, like
 * TransactionDraft — a future Gemini App Functions capability calls the same
 * gateway methods). `startDate` is null only ever as a creation-time
 * convenience: the backend sets it to the creation day (ADR-0024), and
 * afterwards the definition always carries one — an edit always sends a
 * date, never null.
 */
data class RecurringCostDraft(
    val name: String,
    val amount: String,
    val intervalValue: Int,
    val intervalUnit: IntervalUnit,
    val startDate: String? = null,
)

/** The recurring-cost operations screens call (UI-independent). */
interface RecurringCostGateway {
    suspend fun fetchRecurringCosts(): List<RecurringCostDto>
    suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto
    suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto
    suspend fun deleteRecurringCost(id: Int)

    /** The Occurrences section's read (web ADR-0026): every non-Paid
     * Occurrence with its skipped state, newest first — the one order the
     * edit modal renders. */
    suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto>

    /** The per-Occurrence skip write (web ADR-0026): state the row's
     * skipped state — skip or un-skip — and answer the refreshed read. */
    suspend fun setOccurrenceSkipped(id: Int, occurrenceDate: String, skipped: Boolean): List<RecurringOccurrenceDto>
}

/** The API-backed RecurringCostGateway (web issue #56). */
class ApiRecurringCostRepository(private val api: RecurringCostApi) : RecurringCostGateway {

    override suspend fun fetchRecurringCosts(): List<RecurringCostDto> =
        call { api.list() }

    override suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto =
        call {
            api.create(
                RecurringCostCreateRequest(
                    name = draft.name,
                    amount = draft.amount,
                    interval_value = draft.intervalValue,
                    interval_unit = draft.intervalUnit,
                    start_date = draft.startDate,
                ),
            )
        }

    override suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto =
        call {
            api.update(
                id,
                RecurringCostUpdateRequest(
                    name = draft.name,
                    amount = draft.amount,
                    interval_value = draft.intervalValue,
                    interval_unit = draft.intervalUnit,
                    // An edit always sends a date: a definition always
                    // carries its start date, it can be changed, never unset
                    // (ADR-0024) — the backend rejects an explicit null, so
                    // a null draft is a caller bug, failed here at the seam.
                    start_date = requireNotNull(draft.startDate) {
                        "An edited recurring cost always carries its start date (ADR-0024)."
                    },
                ),
            )
        }

    override suspend fun deleteRecurringCost(id: Int) {
        call { api.delete(id) }
    }

    override suspend fun fetchOccurrences(id: Int): List<RecurringOccurrenceDto> =
        call { api.occurrences(id) }

    override suspend fun setOccurrenceSkipped(
        id: Int,
        occurrenceDate: String,
        skipped: Boolean,
    ): List<RecurringOccurrenceDto> =
        call { api.setOccurrenceSkipped(id, occurrenceDate, RecurringOccurrenceUpdateRequest(skipped)) }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
