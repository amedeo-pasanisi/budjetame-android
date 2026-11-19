package com.budjetame.android.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * One user turn (web ADR-0035, issue #128): the transcript exactly as the
 * client last received it — the previous turns' tails joined — plus the new
 * text. The messages are carried as opaque JSON: the client renders only
 * the user's text and the reply, and sends everything else (tool calls and
 * tool results included) back untouched, so a block detail changing on the
 * server never breaks the client.
 */
@Serializable
data class AssistantTurnRequest(
    val transcript: List<JsonObject>,
    val text: String,
)

/** The turn's answer: the written reply in the Account's Locale and the
 * messages appended this turn, the user message first. */
@Serializable
data class AssistantTurnDto(
    val reply: String,
    val transcript_tail: List<JsonObject>,
)

/**
 * The Assistant's turn endpoint, stateless. A successful turn is a write
 * through the transport, so it bumps DataVersion (ADR-0002) and the
 * Transactions tab refetches its Draft Transactions. 409 no Assistant
 * Settings; 429 the hourly bound, the `detail` naming the wait in the
 * Locale; 501/502 the provider could not answer. A provider that rejected
 * the key or is busy answers 200 with a reply saying so.
 */
interface AssistantApi {

    @POST("assistant/turns")
    suspend fun turn(@Body body: AssistantTurnRequest): AssistantTurnDto
}
