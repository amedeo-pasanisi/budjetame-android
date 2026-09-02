package com.budjetame.android.data.transaction

import com.budjetame.android.data.api.TRANSACTION_PAGE_LIMIT
import com.budjetame.android.data.api.TransactionApi
import com.budjetame.android.data.api.TransactionPageDto
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
}

/** The API-backed TransactionGateway (web issues #17/#30). */
class ApiTransactionRepository(private val api: TransactionApi) : TransactionGateway {

    override suspend fun fetchPage(
        filters: TransactionFilters,
        cursor: String?,
        limit: Int,
    ): TransactionPageDto = try {
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
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
