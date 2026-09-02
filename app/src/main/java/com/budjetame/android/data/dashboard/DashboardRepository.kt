package com.budjetame.android.data.dashboard

import com.budjetame.android.data.api.DashboardApi
import com.budjetame.android.data.api.DashboardSummaryDto
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/** The dashboard operations the screen calls (UI-independent). */
interface DashboardGateway {
    /** The overview for one reference month ("YYYY-MM"). */
    suspend fun fetchSummary(month: String): DashboardSummaryDto
}

/** The API-backed DashboardGateway (web issue #17). */
class ApiDashboardRepository(private val api: DashboardApi) : DashboardGateway {

    override suspend fun fetchSummary(month: String): DashboardSummaryDto = try {
        api.summary(month)
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
