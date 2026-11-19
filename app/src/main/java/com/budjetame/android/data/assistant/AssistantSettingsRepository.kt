package com.budjetame.android.data.assistant

import com.budjetame.android.data.api.AssistantModelDto
import com.budjetame.android.data.api.AssistantProvider
import com.budjetame.android.data.api.AssistantSettingsApi
import com.budjetame.android.data.api.AssistantSettingsDto
import com.budjetame.android.data.api.AssistantSettingsRequest
import com.budjetame.android.data.api.toApiException
import retrofit2.HttpException

/** The Assistant Settings operations the Settings dialog calls. */
interface AssistantSettingsGateway {
    suspend fun fetchStatus(): AssistantSettingsDto

    /** A blank model is sent as null: the provider's default. */
    suspend fun save(provider: AssistantProvider, key: String, model: String): AssistantSettingsDto

    suspend fun remove()

    /** `unsavedKey` is the key typed but not saved yet, or null to let the
     * server use the stored key of that provider. */
    suspend fun fetchModels(provider: AssistantProvider, unsavedKey: String?): List<AssistantModelDto>
}

/** The API-backed AssistantSettingsGateway. */
class ApiAssistantSettingsRepository(private val api: AssistantSettingsApi) : AssistantSettingsGateway {

    override suspend fun fetchStatus(): AssistantSettingsDto = call { api.status() }

    override suspend fun save(provider: AssistantProvider, key: String, model: String): AssistantSettingsDto =
        call { api.save(AssistantSettingsRequest(provider = provider, key = key, model = model.trim().ifEmpty { null })) }

    override suspend fun remove() {
        call { api.remove() }
    }

    override suspend fun fetchModels(provider: AssistantProvider, unsavedKey: String?): List<AssistantModelDto> =
        call { api.models(provider.wire, unsavedKey?.trim()?.ifEmpty { null }) }.models

    private suspend fun <T> call(block: suspend () -> T): T = try {
        block()
    } catch (error: HttpException) {
        throw error.toApiException()
    }
}
