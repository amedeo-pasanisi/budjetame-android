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
 * `location` is the optional Geographic Location's coordinates (ticket
 * #29); `place` is its optional Place reference (ADR-0005 parity). The
 * location keys are always sent on the wire — values or explicit nulls
 * (the backend applies a present key even when null, so a removed location
 * clears); the Place never travels without coordinates, and a
 * coordinates-only pick or GPS clears it.
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
    /** The Geographic Location's coordinates (ticket #29), null = none. */
    val location: LatLng? = null,
    /** The optional Place reference (ADR-0005 parity); only meaningful
     * with `location` — the wire mapping never sends it without
     * coordinates. */
    val place: Place? = null,
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
            val location = locationFields(draft)
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
                    // The location's four keys travel whenever the form
                    // carries a location (a fresh create has nothing to
                    // clear, so a locationless create omits them).
                    latitude = location.latitude,
                    longitude = location.longitude,
                    place_name = location.place_name,
                    place_id = location.place_id,
                ),
            )
        }

    override suspend fun updateTransaction(id: Int, draft: TransactionDraft): TransactionDto {
        // The location is always sent on an edit — values set it, explicit
        // nulls clear it (the backend applies a present key even when null,
        // ADR-0005): the form holds the whole location state, so the stored
        // keys can never stay behind. The Place never travels without
        // coordinates: a coordinates-only pick (free-map tap, GPS) or a
        // Remove clears it with them.
        val wire = locationFields(draft)
        return call {
            when {
                draft.type == TransactionType.TRANSFER -> {
                    api.updateTransfer(
                        id,
                        TransactionTransferUpdateRequest(
                            amount = draft.amount,
                            date = draft.date,
                            description = draft.description,
                            latitude = wire.latitude,
                            longitude = wire.longitude,
                            place_name = wire.place_name,
                            place_id = wire.place_id,
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
                            latitude = wire.latitude,
                            longitude = wire.longitude,
                            place_name = wire.place_name,
                            place_id = wire.place_id,
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
                            latitude = wire.latitude,
                            longitude = wire.longitude,
                            place_name = wire.place_name,
                            place_id = wire.place_id,
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
                            latitude = wire.latitude,
                            longitude = wire.longitude,
                            place_name = wire.place_name,
                            place_id = wire.place_id,
                        ),
                    )
                }
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

    /** The wire's four location keys for a draft (ticket #29): coordinates
     * serialized through the shared helpers, and the Place only when it can
     * accompany them — a Place without coordinates is outside the model
     * (CONTEXT.md), so a locationless draft clears the place keys too. */
    private fun locationFields(draft: TransactionDraft): LocationFields {
        val wire = latLngToWire(draft.location)
        val place = draft.place.takeIf { draft.location != null }
        return LocationFields(
            latitude = wire.latitude,
            longitude = wire.longitude,
            place_name = place?.name,
            place_id = place?.placeId,
        )
    }

    /** The four location keys of a request body. */
    private data class LocationFields(
        val latitude: String?,
        val longitude: String?,
        val place_name: String?,
        val place_id: String?,
    )
}
