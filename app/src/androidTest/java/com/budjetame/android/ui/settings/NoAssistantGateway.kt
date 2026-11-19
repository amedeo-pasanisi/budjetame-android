package com.budjetame.android.ui.settings

import com.budjetame.android.data.api.ApiException
import com.budjetame.android.data.api.AssistantTurnDto
import com.budjetame.android.data.assistant.AssistantGateway
import kotlinx.serialization.json.JsonObject

/** The Assistant of an Account without Assistant Settings, beside
 * [NoAssistantSettingsGateway]: every turn is refused with 409, as the
 * server does. The shell tests never open it (its button is hidden). */
class NoAssistantGateway : AssistantGateway {
    override suspend fun sendTurn(transcript: List<JsonObject>, text: String): AssistantTurnDto =
        throw ApiException(status = 409, detail = "No Assistant Settings", message = "No Assistant Settings")
}
