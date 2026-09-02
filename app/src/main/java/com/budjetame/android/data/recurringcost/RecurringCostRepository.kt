package com.budjetame.android.data.recurringcost

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringCostApi
import com.budjetame.android.data.api.RecurringCostCreateRequest
import com.budjetame.android.data.api.RecurringCostDto
import com.budjetame.android.data.api.RecurringCostUpdateRequest
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/**
 * The fields the Recurring Cost form drafts (UI-independent, like
 * TransactionDraft — a future Gemini App Functions capability calls the same
 * gateway methods). `startDate` null = unset (the creation date); the
 * due-date override (`dueDay`, `dueMonth`) is already shaped to the
 * interval unit by the form: a day-of-month alone for months, a month+day
 * pair for years, nothing for days/weeks.
 */
data class RecurringCostDraft(
    val name: String,
    val amount: String,
    val intervalValue: Int,
    val intervalUnit: IntervalUnit,
    val startDate: String? = null,
    val dueDay: Int? = null,
    val dueMonth: Int? = null,
)

/** The recurring-cost operations screens call (UI-independent). */
interface RecurringCostGateway {
    suspend fun fetchRecurringCosts(): List<RecurringCostDto>
    suspend fun createRecurringCost(draft: RecurringCostDraft): RecurringCostDto
    suspend fun updateRecurringCost(id: Int, draft: RecurringCostDraft): RecurringCostDto
    suspend fun deleteRecurringCost(id: Int)
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
                    due_day = draft.dueDay,
                    due_month = draft.dueMonth,
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
                    start_date = draft.startDate,
                    due_day = draft.dueDay,
                    due_month = draft.dueMonth,
                ),
            )
        }

    override suspend fun deleteRecurringCost(id: Int) {
        call { api.delete(id) }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
