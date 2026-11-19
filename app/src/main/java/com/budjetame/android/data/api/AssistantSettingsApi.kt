package com.budjetame.android.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Query

/** The fixed provider list an Assistant may run on (CONTEXT.md: Assistant
 * Settings), in the order the Settings picker shows it; the wire values
 * match the backend's enum; `wire` is the same value for the query string,
 * which Retrofit writes with toString(). */
@Serializable
enum class AssistantProvider(val wire: String, val label: String) {
    @SerialName("anthropic") ANTHROPIC("anthropic", "Anthropic"),
    @SerialName("openrouter") OPENROUTER("openrouter", "OpenRouter"),
    @SerialName("gemini") GEMINI("gemini", "Google Gemini"),
}

/** The status of the Account's Assistant Settings: everything but the
 * Assistant Key, of which only the last four characters come back. */
@Serializable
data class AssistantSettingsDto(
    val configured: Boolean,
    val provider: AssistantProvider? = null,
    val model: String? = null,
    val last4: String? = null,
)

/** Set or replace the Assistant Settings in one call; a null model means
 * the provider's default. */
@Serializable
data class AssistantSettingsRequest(
    val provider: AssistantProvider,
    val key: String,
    val model: String?,
)

/** One model of a provider's list for the picker. */
@Serializable
data class AssistantModelDto(
    val id: String,
    val label: String,
    val free: Boolean,
)

@Serializable
data class AssistantModelsDto(
    val provider: AssistantProvider,
    val models: List<AssistantModelDto>,
)

/**
 * Assistant Settings resource (web issues #126, #127; ADR-0035 there). The
 * Assistant Key goes up in `save` and never comes back.
 */
interface AssistantSettingsApi {

    @GET("assistant/settings")
    suspend fun status(): AssistantSettingsDto

    /** 422 with a string detail when the server rejects the settings. */
    @PUT("assistant/settings")
    suspend fun save(@Body body: AssistantSettingsRequest): AssistantSettingsDto

    @DELETE("assistant/settings")
    suspend fun remove()

    /** The provider's models. `key` is a key not yet saved, sent in a
     * header (never the query string) so a provider that lists only with a
     * key can list before Save; null sends no header. 409 no key where one
     * is needed, 422 the provider rejected the key, 502 list unavailable. */
    @GET("assistant/models")
    suspend fun models(
        @Query("provider") provider: String,
        @Header("X-Assistant-Key") key: String?,
    ): AssistantModelsDto
}
