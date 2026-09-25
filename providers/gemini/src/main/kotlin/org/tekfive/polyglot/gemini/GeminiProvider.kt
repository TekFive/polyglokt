package org.tekfive.polyglot.gemini

import com.google.genai.Client
import com.google.genai.errors.ApiException
import com.google.genai.types.Blob
import com.google.genai.types.Content
import com.google.genai.types.ClientOptions
import com.google.genai.types.HttpOptions
import com.google.genai.types.EmbedContentConfig
import com.google.genai.types.FunctionCallingConfig
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.GenerateContentConfig
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.google.genai.types.ThinkingConfig
import com.google.genai.types.Tool
import com.google.genai.types.ToolConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.ChatResponse
import org.tekfive.polyglot.ContentPart
import org.tekfive.polyglot.ContentSource
import org.tekfive.polyglot.EmbeddingProvider
import org.tekfive.polyglot.EmbeddingRequest
import org.tekfive.polyglot.EmbeddingResponse
import org.tekfive.polyglot.FinishReason
import org.tekfive.polyglot.MessageRole
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.ReasoningEffort
import org.tekfive.polyglot.ResponseFormat
import org.tekfive.polyglot.StreamEvent
import org.tekfive.polyglot.ToolChoice
import org.tekfive.polyglot.CachedContentProvider
import org.tekfive.polyglot.CachedContentHandle
import org.tekfive.polyglot.CreateCachedContentRequest
import org.tekfive.polyglot.validateCaching
import com.google.genai.types.CreateCachedContentConfig
import com.google.genai.types.UpdateCachedContentConfig
import java.time.Duration
import org.tekfive.polyglot.Usage
import java.util.Base64
import okhttp3.OkHttpClient

class GeminiConfig(
    internal val apiKey: String? = null,
    internal val vertexAi: Boolean = false,
    internal val project: String? = null,
    internal val location: String? = null,
    internal val baseUrl: String? = null,
    internal val httpClient: OkHttpClient? = null,
) {
    init {
        require(vertexAi || !apiKey.isNullOrBlank()) { "apiKey is required unless Vertex AI is enabled" }
        if (vertexAi) require(!project.isNullOrBlank() && !location.isNullOrBlank()) {
            "project and location are required for Vertex AI"
        }
    }

    override fun toString() = "GeminiConfig(apiKey=${if (apiKey == null) null else "<redacted>"}, vertexAi=$vertexAi, project=$project, location=$location)"
}

class GeminiProvider private constructor(
    private val client: Client,
) : ChatProvider, EmbeddingProvider, CachedContentProvider, AutoCloseable {
    constructor(config: GeminiConfig) : this(
        Client.builder()
            .vertexAI(config.vertexAi)
            .apply {
                config.apiKey?.let(::apiKey)
                config.project?.let(::project)
                config.location?.let(::location)
                config.baseUrl?.let { httpOptions(HttpOptions.builder().baseUrl(it).build()) }
                config.httpClient?.let { clientOptions(ClientOptions.builder().customHttpClient(it).build()) }
            }
            .build(),
    )

    override val id = ID
    override val capabilities = CAPABILITIES

    override suspend fun complete(request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        val config = request.toConfig()
        sdkCall { client.models.generateContent(request.target.model, request.toContents(), config) }
            .toPolyglot(request)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val content = mutableListOf<ContentPart>()
        val text = StringBuilder()
        var usage = Usage()
        var finishReason = FinishReason.UNKNOWN
        val config = request.toConfig()
        sdkCall { client.models.generateContentStream(request.target.model, request.toContents(), config) }
            .use { stream ->
                for (chunk in stream) {
                    chunk.usageMetadata().orElse(null)?.let {
                        usage = Usage(
                            it.promptTokenCount().orElse(null)?.toLong(),
                            it.candidatesTokenCount().orElse(null)?.toLong(),
                            it.thoughtsTokenCount().orElse(null)?.toLong(),
                            cacheReadInputTokens = it.cachedContentTokenCount().orElse(null)?.toLong(),
                        )
                    }
                    finishReason = chunk.finishReason().toString().toFinishReason()
                    chunk.parts().orEmpty().forEach { part ->
                        val delta = part.text().orElse(null)
                        if (delta != null) {
                            if (part.thought().orElse(false)) emit(StreamEvent.ReasoningDelta(delta))
                            else {
                                text.append(delta)
                                emit(StreamEvent.TextDelta(delta))
                            }
                        }
                        part.functionCall().orElse(null)?.let { call ->
                            val toolCall = call.toPolyglot()
                            content += toolCall
                            emit(StreamEvent.ToolCallDelta(content.size - 1, toolCall.id, toolCall.name, toolCall.arguments.toString()))
                        }
                    }
                }
            }
        if (text.isNotEmpty()) content.add(0, ContentPart.Text(text.toString()))
        emit(StreamEvent.Completed(ChatResponse(request.target, content, usage, finishReason)))
    }.flowOn(Dispatchers.IO)

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse = withContext(Dispatchers.IO) {
        val config = EmbedContentConfig.builder()
            .apply { request.dimensions?.let(::outputDimensionality) }
            .build()
        val response = sdkCall { client.models.embedContent(request.target.model, request.input, config) }
        EmbeddingResponse(
            request.target,
            response.embeddings().orElse(emptyList()).map { embedding ->
                embedding.values().orElse(emptyList()).toFloatArray()
            },
        )
    }

    override suspend fun createCachedContent(request: CreateCachedContentRequest): CachedContentHandle = withContext(Dispatchers.IO) {
        require(request.target.provider == id) { "Cache target belongs to a different provider" }
        val chat = ChatRequest(request.target, request.messages, tools = request.tools, toolChoice = request.toolChoice)
        val generation = chat.toConfig()
        val config = CreateCachedContentConfig.builder()
            .contents(chat.toContents())
            .apply {
                generation.systemInstruction().ifPresent { systemInstruction(it) }
                generation.tools().ifPresent { tools(it) }
                generation.toolConfig().ifPresent { toolConfig(it) }
                request.ttl?.let { ttl(it) }
                request.displayName?.let { displayName(it) }
            }.build()
        sdkCall { client.caches.create(request.target.model, config) }.toHandle(request.target, request.tools.map { it.name }.toSet())
    }

    override suspend fun getCachedContent(handle: CachedContentHandle): CachedContentHandle = withContext(Dispatchers.IO) {
        require(handle.target.provider == id) { "Cache belongs to a different provider" }
        sdkCall { client.caches.get(handle.id, null) }.toHandle(handle.target, handle.toolNames)
    }

    override suspend fun updateCachedContent(handle: CachedContentHandle, ttl: Duration): CachedContentHandle = withContext(Dispatchers.IO) {
        require(handle.target.provider == id) { "Cache belongs to a different provider" }
        require(!ttl.isNegative && !ttl.isZero) { "Cache TTL must be positive" }
        sdkCall { client.caches.update(handle.id, UpdateCachedContentConfig.builder().ttl(ttl).build()) }.toHandle(handle.target, handle.toolNames)
    }

    override suspend fun deleteCachedContent(handle: CachedContentHandle): Unit = withContext(Dispatchers.IO) {
        require(handle.target.provider == id) { "Cache belongs to a different provider" }
        sdkCall { client.caches.delete(handle.id, null) }
        Unit
    }

    private fun com.google.genai.types.CachedContent.toHandle(target: org.tekfive.polyglot.ModelTarget, toolNames: Set<String>) = CachedContentHandle(
        target = target,
        id = name().orElseThrow { ProviderException(ID, ProviderErrorKind.INVALID_RESPONSE, "Gemini returned no cache ID") },
        expiresAt = expireTime().orElse(null),
        inputTokens = usageMetadata().orElse(null)?.totalTokenCount()?.orElse(null)?.toLong(),
        toolNames = toolNames,
    )

    override fun close() = client.close()

    private fun ChatRequest.toContents(): List<Content> = messages
        .filter { it.role != MessageRole.SYSTEM }
        .map { message ->
            Content.builder()
                .role(if (message.role == MessageRole.ASSISTANT) "model" else "user")
                .parts(message.content.map { it.toPart() })
                .build()
        }

    private fun ContentPart.toPart(): Part = when (this) {
        is ContentPart.Text -> Part.fromText(text)
        is ContentPart.Image -> source.toPart()
        is ContentPart.Document -> source.toPart()
        is ContentPart.Audio -> source.toPart()
        is ContentPart.ToolCall -> Part.fromFunctionCall(name, arguments.toAnyMap())
        is ContentPart.ToolResult -> Part.fromFunctionResponse(name, result.toAnyMap())
    }

    private fun ContentSource.toPart(): Part = when (this) {
        is ContentSource.Url -> Part.fromUri(url, mediaType)
        is ContentSource.Base64 -> Part.builder()
            .inlineData(Blob.builder().mimeType(mediaType).data(Base64.getDecoder().decode(data)).build())
            .build()
    }

    private fun ChatRequest.toConfig(): GenerateContentConfig {
        validateCaching(this@GeminiProvider)
        require(providerOptions.isEmpty()) { "Gemini providerOptions are not yet supported; use the typed request options" }
        val builder = GenerateContentConfig.builder()
        caching?.cachedContent?.let { builder.cachedContent(it.id) }
        messages.filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { it.text }
            .takeIf { it.isNotBlank() }
            ?.let { builder.systemInstruction(Content.fromParts(Part.fromText(it))) }
        options.temperature?.let { builder.temperature(it.toFloat()) }
        options.maxOutputTokens?.let(builder::maxOutputTokens)
        options.topP?.let { builder.topP(it.toFloat()) }
        options.topK?.let { builder.topK(it.toFloat()) }
        if (options.stopSequences.isNotEmpty()) builder.stopSequences(options.stopSequences)
        options.presencePenalty?.let { builder.presencePenalty(it.toFloat()) }
        options.frequencyPenalty?.let { builder.frequencyPenalty(it.toFloat()) }
        options.reasoningEffort?.takeUnless { it == ReasoningEffort.NONE }?.let {
            builder.thinkingConfig(
                ThinkingConfig.builder().includeThoughts(true).thinkingLevel(it.name).build(),
            )
        }
        (responseFormat as? ResponseFormat.JsonSchema)?.let {
            builder.responseMimeType("application/json")
            builder.responseJsonSchema(it.schema.toAnyMap())
        }
        if (tools.isNotEmpty()) {
            val declarations = tools.map {
                FunctionDeclaration.builder()
                    .name(it.name)
                    .description(it.description)
                    .parametersJsonSchema(it.inputSchema.toAnyMap())
                    .build()
            }
            builder.tools(Tool.builder().functionDeclarations(declarations).build())
        }
        toolChoice?.let { choice ->
            val functionConfig = FunctionCallingConfig.builder()
            when (choice) {
                ToolChoice.Auto -> functionConfig.mode("AUTO")
                ToolChoice.None -> functionConfig.mode("NONE")
                ToolChoice.Required -> functionConfig.mode("ANY")
                is ToolChoice.Named -> functionConfig.mode("ANY").allowedFunctionNames(choice.name)
            }
            builder.toolConfig(ToolConfig.builder().functionCallingConfig(functionConfig.build()).build())
        }
        return builder.build()
    }

    private fun GenerateContentResponse.toPolyglot(request: ChatRequest): ChatResponse {
        val content = buildList {
            parts().orEmpty().forEach { part ->
                part.text().orElse(null)?.takeIf { it.isNotEmpty() && !part.thought().orElse(false) }
                    ?.let { add(ContentPart.Text(it)) }
                part.functionCall().orElse(null)?.let { add(it.toPolyglot()) }
            }
        }
        val reasoning = parts().orEmpty()
            .filter { it.thought().orElse(false) }
            .joinToString("") { it.text().orElse("") }
        val usage = usageMetadata().orElse(null)
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        return ChatResponse(
            request.target,
            content,
            Usage(
                usage?.promptTokenCount()?.orElse(null)?.toLong(),
                usage?.candidatesTokenCount()?.orElse(null)?.toLong(),
                usage?.thoughtsTokenCount()?.orElse(null)?.toLong(),
                cacheReadInputTokens = usage?.cachedContentTokenCount()?.orElse(null)?.toLong(),
            ),
            finishReason().toString().toFinishReason(),
            if (request.responseFormat is ResponseFormat.JsonSchema && text.isNotBlank()) {
                runCatching { JSON.parseToJsonElement(text) }.getOrNull()
            } else null,
            responseId().orElse(null)?.let { mapOf("requestId" to it) }.orEmpty(),
            reasoning.takeIf { it.isNotEmpty() },
        )
    }

    private fun com.google.genai.types.FunctionCall.toPolyglot(): ContentPart.ToolCall {
        val arguments = JsonObject(args().orElse(emptyMap()).mapValues { (_, value) -> value.toJsonElement() })
        return ContentPart.ToolCall(id().orElse(name().orElse("tool")), name().orElse("tool"), arguments)
    }

    private inline fun <T> sdkCall(block: () -> T): T = try {
        block()
    } catch (error: ApiException) {
        val status = error.code()
        val kind = when (status) {
            400, 422 -> ProviderErrorKind.INVALID_REQUEST
            401 -> ProviderErrorKind.AUTHENTICATION
            403 -> ProviderErrorKind.PERMISSION
            404 -> ProviderErrorKind.NOT_FOUND
            408 -> ProviderErrorKind.TIMEOUT
            429 -> ProviderErrorKind.RATE_LIMIT
            in 500..599 -> ProviderErrorKind.PROVIDER_UNAVAILABLE
            else -> ProviderErrorKind.UNKNOWN
        }
        throw ProviderException(ID, kind, error.message ?: "Gemini request failed", status, cause = error)
    } catch (error: RuntimeException) {
        throw ProviderException(ID, ProviderErrorKind.NETWORK, error.message ?: "Gemini request failed", cause = error)
    }

    companion object {
        val ID = ProviderId("gemini")
        val CAPABILITIES = setOf(
            Capability.PROMPT_CACHING,
            Capability.CACHED_CONTENT,
            Capability.CHAT,
            Capability.STREAMING,
            Capability.STREAMING_TOOL_CALLS,
            Capability.STRUCTURED_OUTPUT,
            Capability.TOOLS,
            Capability.REASONING,
            Capability.IMAGE_INPUT,
            Capability.DOCUMENT_INPUT,
            Capability.AUDIO_INPUT,
            Capability.EMBEDDINGS,
        )
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun String.toFinishReason() = when (uppercase()) {
    "STOP" -> FinishReason.STOP
    "MAX_TOKENS" -> FinishReason.MAX_TOKENS
    "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

private fun JsonObject.toAnyMap(): Map<String, Any> = mapValues { (_, value) -> value.toAny() ?: "null" }

private fun JsonElement.toAnyMap(): Map<String, Any> = (this as? JsonObject)?.toAnyMap() ?: mapOf("result" to (toAny() ?: "null"))

private fun JsonElement.toAny(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> toAnyMap()
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
}

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    is Boolean -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    else -> JsonPrimitive(toString())
}
