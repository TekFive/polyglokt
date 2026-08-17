package org.tekfive.polyglot

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

class PolyglotClient(
    providers: Iterable<ModelProvider>,
    private val fallbackPolicy: FallbackPolicy = FallbackPolicy.RetryableOnly,
) {
    private val providerList = providers.toList()
    private val providerById = providerList.associateBy { it.id }

    init {
        require(providerList.map { it.id }.toSet().size == providerList.size) { "Provider IDs must be unique" }
    }

    suspend fun complete(request: ChatRequest): ChatResponse {
        val targets = listOf(request.target) + request.fallbackTargets
        var lastFailure: ProviderException? = null

        targets.forEachIndexed { index, target ->
            val provider = chatProvider(target.provider)
            requireCapabilities(provider, request)
            try {
                return provider.complete(request.forTarget(target))
            } catch (error: ProviderException) {
                lastFailure = error
                val next = targets.getOrNull(index + 1) ?: throw error
                if (!fallbackPolicy.shouldFallback(error, next)) throw error
            }
        }

        throw lastFailure ?: PolyglotException("No chat target was available")
    }

    suspend fun completeJson(request: ChatRequest): JsonElement {
        require(request.responseFormat is ResponseFormat.JsonSchema) {
            "completeJson requires ResponseFormat.JsonSchema"
        }
        val response = complete(request)
        return response.structuredOutput ?: runCatching { Json.parseToJsonElement(response.text) }
            .getOrElse { throw PolyglotException("Provider returned invalid structured output", it) }
    }

    fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val targets = listOf(request.target) + request.fallbackTargets
        var lastFailure: ProviderException? = null

        for ((index, target) in targets.withIndex()) {
            val provider = chatProvider(target.provider)
            requireCapabilities(provider, request, streaming = true)
            var emittedContent = false
            try {
                provider.stream(request.forTarget(target)).collect { event ->
                    if (event !is StreamEvent.Completed) emittedContent = true
                    emit(event)
                }
                return@flow
            } catch (error: ProviderException) {
                lastFailure = error
                val next = targets.getOrNull(index + 1) ?: throw error
                if (emittedContent || !fallbackPolicy.shouldFallback(error, next)) throw error
            }
        }

        throw lastFailure ?: PolyglotException("No streaming target was available")
    }

    suspend fun embed(request: EmbeddingRequest): EmbeddingResponse {
        val targets = listOf(request.target) + request.fallbackTargets
        var lastFailure: ProviderException? = null

        targets.forEachIndexed { index, target ->
            val provider = provider(target.provider) as? EmbeddingProvider
                ?: throw UnsupportedCapabilityException(target.provider, Capability.EMBEDDINGS)
            try {
                return provider.embed(request.forTarget(target))
            } catch (error: ProviderException) {
                lastFailure = error
                val next = targets.getOrNull(index + 1) ?: throw error
                if (!fallbackPolicy.shouldFallback(error, next)) throw error
            }
        }

        throw lastFailure ?: PolyglotException("No embedding target was available")
    }

    fun provider(id: ProviderId): ModelProvider = providerById[id] ?: throw ProviderNotRegisteredException(id)

    private fun chatProvider(id: ProviderId): ChatProvider = provider(id) as? ChatProvider
        ?: throw UnsupportedCapabilityException(id, Capability.CHAT)

    private fun requireCapabilities(provider: ChatProvider, request: ChatRequest, streaming: Boolean = false) {
        val required = buildSet {
            add(Capability.CHAT)
            if (streaming) add(Capability.STREAMING)
            if (request.tools.isNotEmpty()) {
                add(Capability.TOOLS)
                if (streaming) add(Capability.STREAMING_TOOL_CALLS)
            }
            if (request.responseFormat is ResponseFormat.JsonSchema) add(Capability.STRUCTURED_OUTPUT)
            if (request.options.reasoningEffort != null) add(Capability.REASONING)
            request.messages.flatMap { it.content }.forEach {
                when (it) {
                    is ContentPart.Image -> add(Capability.IMAGE_INPUT)
                    is ContentPart.Document -> add(Capability.DOCUMENT_INPUT)
                    is ContentPart.Audio -> add(Capability.AUDIO_INPUT)
                    else -> Unit
                }
            }
        }
        required.firstOrNull { it !in provider.capabilities }?.let {
            throw UnsupportedCapabilityException(provider.id, it)
        }
    }
}
