package com.budjetame.android.ui.transactions

import com.budjetame.android.data.api.DraftTransactionDto
import com.budjetame.android.data.api.TransactionDto
import com.budjetame.android.data.drafttransaction.DraftTransactionGateway
import com.budjetame.android.data.transaction.TransactionDraft

/** An Account with no Draft Transactions (ticket #67): the pinned Drafts
 * block stays absent, so the screens under test render exactly as before. */
internal class NoDraftTransactions : DraftTransactionGateway {
    override suspend fun fetchDrafts(): List<DraftTransactionDto> = emptyList()

    override suspend fun approve(id: Int, edited: TransactionDraft?): TransactionDto = error("no drafts")

    override suspend fun discard(id: Int) = error("no drafts")
}
