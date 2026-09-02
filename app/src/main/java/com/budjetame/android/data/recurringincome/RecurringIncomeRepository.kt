package com.budjetame.android.data.recurringincome

import com.budjetame.android.data.api.IntervalUnit
import com.budjetame.android.data.api.RecurringIncomeApi
import com.budjetame.android.data.api.RecurringIncomeCreateRequest
import com.budjetame.android.data.api.RecurringIncomeDto
import com.budjetame.android.data.api.RecurringIncomeUpdateRequest
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/**
 * The fields the Recurring Income form drafts (UI-independent, like
 * TransactionDraft — a future Gemini App Functions capability calls the same
 * gateway methods). `startDate` null = unset (the creation date); the
 * due-date override (`dueDay`, `dueMonth`) is already shaped to the
 * interval unit by the form: a day-of-month alone for months, a month+day
 * pair for years, nothing for days/weeks.
 */
data class RecurringIncomeDraft(
    val name: String,
    val amount: String,
    val intervalValue: Int,
    val intervalUnit: IntervalUnit,
    val startDate: String? = null,
    val dueDay: Int? = null,
    val dueMonth: Int? = null,
)

/** The recurring-income operations screens call (UI-independent). */
interface RecurringIncomeGateway {
    suspend fun fetchRecurringIncomes(): List<RecurringIncomeDto>
    suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto
    suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto
    suspend fun deleteRecurringIncome(id: Int)

    /** The Skip/Un-skip button (ADR-0016): flips the front of the queue and
     * returns the refreshed definition with its derived state. */
    suspend fun toggleSkipRecurringIncome(id: Int): RecurringIncomeDto
}

/** The API-backed RecurringIncomeGateway (web issue #60), the mirror of
 * ApiRecurringCostRepository (ADR-0011). */
class ApiRecurringIncomeRepository(private val api: RecurringIncomeApi) : RecurringIncomeGateway {

    override suspend fun fetchRecurringIncomes(): List<RecurringIncomeDto> =
        call { api.list() }

    override suspend fun createRecurringIncome(draft: RecurringIncomeDraft): RecurringIncomeDto =
        call {
            api.create(
                RecurringIncomeCreateRequest(
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

    override suspend fun updateRecurringIncome(id: Int, draft: RecurringIncomeDraft): RecurringIncomeDto =
        call {
            api.update(
                id,
                RecurringIncomeUpdateRequest(
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

    override suspend fun deleteRecurringIncome(id: Int) {
        call { api.delete(id) }
    }

    override suspend fun toggleSkipRecurringIncome(id: Int): RecurringIncomeDto =
        call { api.skipToggle(id) }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
