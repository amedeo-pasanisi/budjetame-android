package com.budjetame.android.data.dashboard

import com.budjetame.android.data.api.BudgetDto
import com.budjetame.android.data.api.DashboardApi
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.TrendDto
import com.budjetame.android.data.api.TrendKind
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/** The dashboard operations the screen calls (UI-independent). */
interface DashboardGateway {
    /** The overview for one reference month ("YYYY-MM"). */
    suspend fun fetchSummary(month: String): DashboardSummaryDto

    /** One trend side over the inclusive month range ("YYYY-MM", from ≤ to). */
    suspend fun fetchTrend(kind: TrendKind, fromMonth: String, toMonth: String): TrendDto

    /** The Budget card's frame for the current Europe/Rome month (no month parameter). */
    suspend fun fetchBudget(): BudgetDto
}

/** The API-backed DashboardGateway (web issues #17, #65, #29). */
class ApiDashboardRepository(private val api: DashboardApi) : DashboardGateway {

    override suspend fun fetchSummary(month: String): DashboardSummaryDto = try {
        api.summary(month)
    } catch (error: HttpException) {
        throw error.toApiException()
    }

    override suspend fun fetchTrend(
        kind: TrendKind,
        fromMonth: String,
        toMonth: String,
    ): TrendDto = try {
        when (kind) {
            TrendKind.EXPENSE -> api.expenseTrend(fromMonth, toMonth)
            TrendKind.INCOME -> api.incomeTrend(fromMonth, toMonth)
        }
    } catch (error: HttpException) {
        throw error.toApiException()
    }

    override suspend fun fetchBudget(): BudgetDto = try {
        api.budget()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
