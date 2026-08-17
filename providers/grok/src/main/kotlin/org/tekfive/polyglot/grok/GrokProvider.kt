package org.tekfive.polyglot.grok

import kotlinx.coroutines.flow.Flow
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.ChatResponse
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.StreamEvent
import org.tekfive.polyglot.openaicompatible.OpenAiCompatibleProvider

class GrokConfig(
    internal val apiKey: String,
    internal val baseUrl: String = GrokProvider.BASE_URL,
    internal val conversationId: String? = null,
    internal val extraHeaders: Map<String, String> = emptyMap(),
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        conversationId?.let { require(it.isNotBlank()) { "conversationId must not be blank" } }
    }

    override fun toString() =
        "GrokConfig(apiKey=<redacted>, baseUrl=$baseUrl, conversationId=$conversationId, extraHeaderNames=${extraHeaders.keys})"
}

/**
 * Grok chat adapter using xAI's OpenAI-compatible Chat Completions API.
 *
 * xAI-specific features not represented by the portable API can be supplied through
 * [ChatRequest.providerOptions].
 */
class GrokProvider(
    config: GrokConfig,
) : ChatProvider {
    private val delegate = OpenAiCompatibleProvider(
        id = ID,
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        extraHeaders = buildMap {
            putAll(config.extraHeaders)
            config.conversationId?.let { put(CONVERSATION_ID_HEADER, it) }
        },
        capabilities = CAPABILITIES,
    )

    override val id = ID
    override val capabilities = CAPABILITIES

    override suspend fun complete(request: ChatRequest): ChatResponse = delegate.complete(request)

    override fun stream(request: ChatRequest): Flow<StreamEvent> = delegate.stream(request)

    companion object {
        val ID = ProviderId("grok")
        const val BASE_URL = "https://api.x.ai/v1"
        const val CONVERSATION_ID_HEADER = "x-grok-conv-id"

        val CAPABILITIES = setOf(
            Capability.CHAT,
            Capability.STREAMING,
            Capability.STREAMING_TOOL_CALLS,
            Capability.STRUCTURED_OUTPUT,
            Capability.TOOLS,
            Capability.REASONING,
            Capability.IMAGE_INPUT,
        )
    }
}
