package com.budjetame.android.data.transaction

import com.budjetame.android.data.api.TRANSACTION_PAGE_LIMIT
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionCreateRequest
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionExpenseIncomeUpdateRequest
import com.budjetame.android.data.api.TransactionExpenseLinkUpdateRequest
import com.budjetame.android.data.api.TransactionIncomeLinkUpdateRequest
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionTransferUpdateRequest
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/**
 * The ledger's filter set (web issues #17/#33/#54/#86): the same names the
 * backend accepts, so the listing and the export (M3) share them. Absent
 * values mean "all". The Recurring definition filter (web issue #86)
 * narrows to the Transactions linked to exactly one definition:
 * `recurringCostId` to a Recurring Cost, `recurringIncomeId` to a
 * Recurring Income. At most one of the two is ever set — the filter bar's
 * Recurring select is a single pick, and a Transaction is one type — but
 * each is its own key so a cost and an income that share an id can never
 * be confused.
 */
data class TransactionFilters(
    val walletId: Int? = null,
    val categoryId: Int? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    /** The Recurring Cost definition the list narrows to (web issue #86). */
    val recurringCostId: Int? = null,
    /** The Recurring Income definition the list narrows to (web issue #86). */
    val recurringIncomeId: Int? = null,
    /** The Description needle (ADR-0009): sent only when non-blank; the
     * backend matches it case-insensitively as a literal substring. */
    val q: String? = null,
) {
    /** True when any Filters-bar field is set — the search needle is not a
     * filter-bar field, it rides along with them (ADR-0009). */
    val active: Boolean
        get() = walletId != null || categoryId != null || fromDate != null || toDate != null ||
            recurringCostId != null || recurringIncomeId != null
}

/** The transaction operations screens call (UI-independent). */
interface TransactionGateway {

    /** One page of the ledger, newest first. `cursor` is the opaque token
     * the previous page returned, handed back verbatim. */
    suspend fun fetchPage(
        filters: TransactionFilters,
        cursor: String? = null,
        limit: Int = TRANSACTION_PAGE_LIMIT,
    ): TransactionPageDto

    /** Record an Expense, Income, or Transfer. */
    suspend fun createTransaction(draft: TransactionDraft): TransactionDto

    /** Edit an Expense, Income, or Transfer; type and Wallets cannot change. */
    suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto

    /** Delete a Transaction; the result carries the Cash negative-balance
     * indicator. */
    suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto

    /** The whole filtered ledger as the import template's .xlsx (US 7.3):
     * every Transaction matching `filters` — the same filter set the
     * listing uses, search included — not just the visible page. The file
     * and its dated name are exactly what the backend produced (the
     * client never reshapes the workbook). */
    suspend fun export(filters: TransactionFilters): ExportFile
}

/**
 * The fields a create or edit form drafts — UI-independent, so a future
 * Gemini App Functions capability calls the same gateway methods. `type` is
 * one of EXPENSE, INCOME, or TRANSFER (an Opening Balance is never created
 * or edited here). Expense/Income fill `walletId` (plus an optional
 * `categoryId`); a Transfer fills `sourceWalletId` and `destinationWalletId`.
 * `recurringCostId` is the optional Recurring Cost link (web issue #57) —
 * Expenses only; `recurringIncomeId` is the optional Recurring Income link
 * (web issue #61), the mirror — Incomes only. On create a picked link
 * travels whenever set. On edit the link key travels only when the form
 * changed it (`recurringCostTouched`/`recurringIncomeTouched`, the flag of
 * the type's own link), mirroring the web form: a PATCH field present
 * applies even when null (unlinking, freeing the Occurrence); absent leaves
 * the stored pin untouched — a mere amount or date edit never reassigns the
 * Occurrence a link pays.
 */
data class TransactionDraft(
    val type: TransactionType,
    val amount: String,
    val date: String,
    val walletId: Int? = null,
    val sourceWalletId: Int? = null,
    val destinationWalletId: Int? = null,
    val categoryId: Int? = null,
    val recurringCostId: Int? = null,
    val recurringCostTouched: Boolean = false,
    val recurringIncomeId: Int? = null,
    val recurringIncomeTouched: Boolean = false,
    val description: String? = null,
)

/** The API-backed TransactionGateway (web issues #17/#30, #20's write path). */
class ApiTransactionRepository(private val api: TransactionApi) : TransactionGateway {

    override suspend fun fetchPage(
        filters: TransactionFilters,
        cursor: String?,
        limit: Int,
    ): TransactionPageDto = call {
        api.list(
            walletId = filters.walletId,
            categoryId = filters.categoryId,
            fromDate = filters.fromDate,
            toDate = filters.toDate,
            recurringCostId = filters.recurringCostId,
            recurringIncomeId = filters.recurringIncomeId,
            // A blank or whitespace-only needle means no search (ADR-0009),
            // so the param is omitted rather than sent empty.
            q = filters.q?.takeIf { it.isNotBlank() },
            limit = limit,
            cursor = cursor,
        )
    }

    override suspend fun createTransaction(draft: TransactionDraft): TransactionDto =
        call {
            api.create(
                TransactionCreateRequest(
                    type = draft.type,
                    amount = draft.amount,
                    date = draft.date,
                    wallet_id = draft.walletId,
                    source_wallet_id = draft.sourceWalletId,
                    destination_wallet_id = draft.destinationWalletId,
                    category_id = draft.categoryId,
                    recurring_cost_id = draft.recurringCostId,
                    recurring_income_id = draft.recurringIncomeId,
                    description = draft.description,
                ),
            )
        }

    override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
        call {
            when {
                draft.type == TransactionType.TRANSFER -> {
                    api.updateTransfer(
                        id,
                        TransactionTransferUpdateRequest(
                            amount = draft.amount,
                            date = draft.date,
                            description = draft.description,
                        ),
                    )
                }
                // An Expense whose Recurring Cost link the form changed
                // carries the key — null unlinking, a value (re)linking.
                draft.type == TransactionType.EXPENSE && draft.recurringCostTouched -> {
                    api.updateExpenseLink(
                        id,
                        TransactionExpenseLinkUpdateRequest(
                            amount = draft.amount,
                            date = draft.date,
                            category_id = draft.categoryId,
                            description = draft.description,
                            recurring_cost_id = draft.recurringCostId,
                        ),
                    )
                }
                // An Income whose Recurring Income link the form changed
                // carries the income key — null unlinking, a value
                // (re)linking; never the cost key (the backend rejects it).
                draft.type == TransactionType.INCOME && draft.recurringIncomeTouched -> {
                    api.updateIncomeLink(
                        id,
                        TransactionIncomeLinkUpdateRequest(
                            amount = draft.amount,
                            date = draft.date,
                            category_id = draft.categoryId,
                            description = draft.description,
                            recurring_income_id = draft.recurringIncomeId,
                        ),
                    )
                }
                // Transfer, and Expense/Income with their link untouched: no
                // recurring link key on the wire.
                else -> {
                    api.updateExpenseIncome(
                        id,
                        TransactionExpenseIncomeUpdateRequest(
                            amount = draft.amount,
                            date = draft.date,
                            category_id = draft.categoryId,
                            description = draft.description,
                        ),
                    )
                }
            }
        }

    override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto =
        call { api.delete(id) }

    override suspend fun export(filters: TransactionFilters): ExportFile {
        // A raw-body endpoint: Retrofit does not throw for a non-2xx (the
        // body is .xlsx, not JSON), so the mapping checks the response and
        // reuses the same detail parsing as the HttpException mapping.
        val response = api.export(
            walletId = filters.walletId,
            categoryId = filters.categoryId,
            fromDate = filters.fromDate,
            toDate = filters.toDate,
            recurringCostId = filters.recurringCostId,
            recurringIncomeId = filters.recurringIncomeId,
            // A blank or whitespace-only needle means no search (ADR-0009),
            // so the param is omitted rather than sent empty.
            q = filters.q?.takeIf { it.isNotBlank() },
        )
        if (!response.isSuccessful) throw response.toApiException()
        return ExportFile(
            filename = exportFilename(response.headers()["Content-Disposition"]),
            content = response.body()?.bytes() ?: ByteArray(0),
        )
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
