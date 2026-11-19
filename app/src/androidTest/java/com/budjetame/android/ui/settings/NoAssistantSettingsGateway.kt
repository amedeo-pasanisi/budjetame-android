package com.budjetame.android.ui.settings

import com.budjetame.android.data.api.AssistantModelDto
import com.budjetame.android.data.api.AssistantProvider
import com.budjetame.android.data.api.AssistantSettingsDto
import com.budjetame.android.data.assistant.AssistantSettingsGateway

/** An Account without Assistant Settings, for the shell tests that never
 * open Settings: every read says "not configured", lists nothing, and
 * writes are no-ops. */
class NoAssistantSettingsGateway : AssistantSettingsGateway {
    override suspend fun fetchStatus() = AssistantSettingsDto(configured = false)

    override suspend fun save(provider: AssistantProvider, key: String, model: String) =
        AssistantSettingsDto(configured = false)

    override suspend fun remove() = Unit

    override suspend fun fetchModels(provider: AssistantProvider, unsavedKey: String?) =
        emptyList<AssistantModelDto>()
}
