package com.budjetame.android.data.transaction

import com.budjetame.android.data.api.TRANSACTION_PAGE_LIMIT
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionCreateRequest
import com.budjetame.android.data.api.TransactionDeleteResultDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.TransactionExpenseIncomeUpdateRequest
import com.budjetame.android.data.api.TransactionPageDto
import com.budjetame.android.data.api.TransactionTransferUpdateRequest
import com.budjetame.android.data.api.TransactionType
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/**
 * The ledger's filter set (web issues #17/#33/#54): the same names the
 * backend accepts, so the listing and the export (M3) share them. Absent
 * values mean "all".
 */
data class TransactionFilters(
    val walletId: Int? = null,
    val categoryId: Int? = null,
    val fromDate: String? = null,
    val toDate: String? = null,
    /** The Description needle (ADR-0009): sent only when non-blank; the
     * backend matches it case-insensitively as a literal substring. */
    val q: String? = null,
) {
    /** True when any Filters-bar field is set — the search needle is not a
     * filter-bar field, it rides along with them (ADR-0009). */
    val active: Boolean
        get() = walletId != null || categoryId != null || fromDate != null || toDate != null
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
}

/**
 * The fields a create or edit form drafts — UI-independent, so a future
 * Gemini App Functions capability calls the same gateway methods. `type` is
 * one of EXPENSE, INCOME, or TRANSFER (an Opening Balance is never created
 * or edited here). Expense/Income fill `walletId` (plus an optional
 * `categoryId`); a Transfer fills `sourceWalletId` and `destinationWalletId`.
 */
data class TransactionDraft(
    val type: TransactionType,
    val amount: String,
    val date: String,
    val walletId: Int? = null,
    val sourceWalletId: Int? = null,
    val destinationWalletId: Int? = null,
    val categoryId: Int? = null,
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
                    description = draft.description,
                ),
            )
        }

    override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto =
        call {
            if (draft.type == TransactionType.TRANSFER) {
                api.updateTransfer(
                    id,
                    TransactionTransferUpdateRequest(
                        amount = draft.amount,
                        date = draft.date,
                        description = draft.description,
                    ),
                )
            } else {
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

    override suspend fun deleteTransaction(id: Int): TransactionDeleteResultDto =
        call { api.delete(id) }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
