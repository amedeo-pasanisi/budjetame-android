package com.budjetame.android.data.drafttransaction

import com.budjetame.android.data.api.DraftTransactionApi
import com.budjetame.android.data.api.DraftTransactionDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.api.toApiException
import com.budjetame.android.data.transaction.TransactionDraft
import com.budjetame.android.data.transaction.createRequestOf
import retrofit2.HttpException

/**
 * The Draft Transaction operations the Transactions tab calls (CONTEXT.md;
 * ADR-0036 in the web repo), UI-independent. A Draft counts toward nothing:
 * nothing here feeds a Balance, a Budget, a dashboard figure or an Export.
 */
interface DraftTransactionGateway {

    /** The Account's Drafts, oldest first. */
    suspend fun fetchDrafts(): List<DraftTransactionDto>

    /** Approve: one request creates the Transaction through the ledger's
     * create rules and removes the Draft. `edited` null judges the Draft as
     * it stands (the one tap); otherwise the form's fields replace the
     * Draft's. A broken rule is an ApiException with status 422 and the
     * rule as its `detail`; a Draft that is gone, 403. */
    suspend fun approve(id: Int, edited: TransactionDraft? = null): TransactionDto

    /** Discard: the Draft is removed, nothing is created, no Undo. */
    suspend fun discard(id: Int)
}

/** The API-backed DraftTransactionGateway (web issues #122/#123/#125). */
class ApiDraftTransactionRepository(private val api: DraftTransactionApi) : DraftTransactionGateway {

    override suspend fun fetchDrafts(): List<DraftTransactionDto> = call { api.list() }

    override suspend fun approve(id: Int, edited: TransactionDraft?): TransactionDto = call {
        if (edited == null) api.approve(id) else api.approveEdited(id, createRequestOf(edited))
    }

    override suspend fun discard(id: Int) {
        call { api.discard(id) }
    }

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
