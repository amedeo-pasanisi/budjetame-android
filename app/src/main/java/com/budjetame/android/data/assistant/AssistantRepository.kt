package com.budjetame.android.data.assistant

import com.budjetame.android.data.api.AssistantApi
import com.budjetame.android.data.api.AssistantTurnDto
import com.budjetame.android.data.api.AssistantTurnRequest
import com.budjetame.android.data.api.toApiException
import kotlinx.serialization.json.JsonObject
import retrofit2.HttpException

/** The Assistant's one operation: a turn. */
interface AssistantGateway {
    /** `transcript` is the conversation as last received ([] for a new
     * one); a rejected turn throws an ApiException carrying its status. */
    suspend fun sendTurn(transcript: List<JsonObject>, text: String): AssistantTurnDto
}

/** The API-backed AssistantGateway. */
class ApiAssistantRepository(private val api: AssistantApi) : AssistantGateway {

    override suspend fun sendTurn(transcript: List<JsonObject>, text: String): AssistantTurnDto = try {
        api.turn(AssistantTurnRequest(transcript = transcript, text = text))
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
