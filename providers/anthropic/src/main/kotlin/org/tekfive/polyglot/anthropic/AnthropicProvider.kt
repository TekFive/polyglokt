package org.tekfive.polyglot.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.AnthropicClientImpl
import com.anthropic.backends.AnthropicBackend
import com.anthropic.core.ClientOptions
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceAny
import com.anthropic.models.messages.ToolChoiceAuto
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.fasterxml.jackson.databind.JsonNode
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.ChatResponse
import org.tekfive.polyglot.ContentPart
import org.tekfive.polyglot.FinishReason
import org.tekfive.polyglot.MessageRole
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.ReasoningEffort
import org.tekfive.polyglot.ResponseFormat
import org.tekfive.polyglot.StreamEvent
import org.tekfive.polyglot.ToolChoice
import org.tekfive.polyglot.validateCaching
import java.time.Duration
import org.tekfive.polyglot.Usage
import okhttp3.OkHttpClient

class AnthropicConfig(
    internal val apiKey: String,
    internal val baseUrl: String? = null,
    internal val maxRetries: Int = 2,
    internal val httpClient: OkHttpClient? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }

    override fun toString() = "AnthropicConfig(apiKey=<redacted>, baseUrl=$baseUrl, maxRetries=$maxRetries)"
}

class AnthropicProvider private constructor(
    private val client: AnthropicClient,
) : ChatProvider, AutoCloseable {
    constructor(config: AnthropicConfig) : this(createClient(config))

    override val id = ID
    override val capabilities = CAPABILITIES

    override suspend fun complete(request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        sdkCall { client.messages().create(request.toSdk()) }.toPolyglot(request)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val accumulator = MessageAccumulator.create()
        sdkCall { client.messages().createStreaming(request.toSdk()) }.use { response ->
            val iterator = response.stream().iterator()
            while (iterator.hasNext()) {
                val event = iterator.next()
                accumulator.accumulate(event)
                if (event.isContentBlockStart()) {
                    val block = event.asContentBlockStart()
                    if (block.contentBlock().isToolUse()) {
                        val tool = block.contentBlock().asToolUse()
                        emit(StreamEvent.ToolCallDelta(block.index().toInt(), tool.id(), tool.name()))
                    }
                } else if (event.isContentBlockDelta()) {
                    val block = event.asContentBlockDelta()
                    val delta = block.delta()
                    when {
                        delta.isText() -> emit(StreamEvent.TextDelta(delta.asText().text()))
                        delta.isThinking() -> emit(StreamEvent.ReasoningDelta(delta.asThinking().thinking()))
                        delta.isInputJson() -> emit(
                            StreamEvent.ToolCallDelta(
                                block.index().toInt(),
                                argumentsDelta = delta.asInputJson().partialJson(),
                            ),
                        )
                    }
                }
            }
        }
        emit(StreamEvent.Completed(accumulator.message().toPolyglot(request)))
    }.flowOn(Dispatchers.IO)

    override fun close() = client.close()

    @Suppress("DEPRECATION")
    private fun ChatRequest.toSdk(): MessageCreateParams {
        validateCaching(this@AnthropicProvider)
        val cacheControl = caching?.let { cache ->
            require(cache.ttl == null || cache.ttl in setOf(Duration.ofMinutes(5), Duration.ofHours(1))) { "Anthropic cache TTL must be 5 minutes or 1 hour" }
            CacheControlEphemeral.builder().apply {
                cache.ttl?.let { ttl(CacheControlEphemeral.Ttl.of(if (it == Duration.ofHours(1)) "1h" else "5m")) }
            }.build()
        }
        val builder = MessageCreateParams.builder()
            .model(target.model)
            .maxTokens((options.maxOutputTokens ?: 4096).toLong())

        if (caching?.messageBreakpoints?.isNotEmpty() == true) {
            val systemBlocks = messages.mapIndexedNotNull { index, message ->
                if (message.role != MessageRole.SYSTEM) null else TextBlockParam.builder().text(message.text).apply {
                    if (index in caching?.messageBreakpoints.orEmpty()) {
                        require(message.text.isNotBlank()) { "Cannot cache an empty system message" }
                        cacheControl(cacheControl!!)
                    }
                }.build()
            }
            if (systemBlocks.isNotEmpty()) builder.systemOfTextBlockParams(systemBlocks)
        } else {
            messages.filter { it.role == MessageRole.SYSTEM }.joinToString("\n\n") { it.text }
                .takeIf { it.isNotBlank() }?.let(builder::system)
            if (cacheControl != null) builder.putAdditionalBodyProperty("cache_control", JsonValue.from(buildMap {
                put("type", "ephemeral")
                caching?.ttl?.let { put("ttl", if (it == Duration.ofHours(1)) "1h" else "5m") }
            }))
        }

        messages.forEachIndexed { index, message ->
            if (message.role == MessageRole.SYSTEM) return@forEachIndexed
            val blocks = message.content.mapIndexed { partIndex, part ->
                val cached = index in caching?.messageBreakpoints.orEmpty() && partIndex == message.content.lastIndex
                when (part) {
                    is ContentPart.Text -> ContentBlockParam.ofText(TextBlockParam.builder().text(part.text).apply {
                        if (cached) {
                            require(part.text.isNotBlank()) { "Cannot cache an empty text block" }
                            cacheControl(cacheControl!!)
                        }
                    }.build())
                    is ContentPart.ToolCall -> ContentBlockParam.ofToolUse(
                        ToolUseBlockParam.builder()
                            .apply { if (cached) cacheControl(cacheControl!!) }
                            .id(part.id)
                            .name(part.name)
                            .input(
                                ToolUseBlockParam.Input.builder()
                                    .putAllAdditionalProperties(part.arguments.toSdkProperties())
                                    .build(),
                            )
                            .build(),
                    )
                    is ContentPart.ToolResult -> ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .apply { if (cached) cacheControl(cacheControl!!) }
                            .toolUseId(part.callId)
                            .content(part.result.toString())
                            .isError(part.isError)
                            .build(),
                    )
                    else -> error("Anthropic provider currently accepts text and tool content")
                }
            }
            val role = if (message.role == MessageRole.ASSISTANT) MessageParam.Role.ASSISTANT else MessageParam.Role.USER
            builder.addMessage(MessageParam.builder().role(role).contentOfBlockParams(blocks).build())
        }

        options.temperature?.let(builder::temperature)
        options.topP?.let(builder::topP)
        options.topK?.let { builder.topK(it.toLong()) }
        options.stopSequences.forEach(builder::addStopSequence)
        tools.forEach { definition ->
            builder.addTool(
                Tool.builder()
                    .name(definition.name)
                    .description(definition.description)
                    .inputSchema(
                        Tool.InputSchema.builder()
                            .putAllAdditionalProperties(definition.inputSchema.toSdkProperties())
                            .build(),
                    )
                    .strict(true)
                    .build(),
            )
        }
        toolChoice?.let {
            when (it) {
                ToolChoice.Auto -> builder.toolChoice(ToolChoiceAuto.builder().build())
                ToolChoice.None -> builder.toolChoice(ToolChoiceNone.builder().build())
                ToolChoice.Required -> builder.toolChoice(ToolChoiceAny.builder().build())
                is ToolChoice.Named -> builder.toolToolChoice(it.name)
            }
        }

        val outputConfig = OutputConfig.builder()
        var hasOutputConfig = false
        options.reasoningEffort?.takeUnless { it == ReasoningEffort.NONE }?.let {
            outputConfig.effort(OutputConfig.Effort.of(it.name.lowercase()))
            hasOutputConfig = true
        }
        (responseFormat as? ResponseFormat.JsonSchema)?.let { format ->
            val schema = JsonOutputFormat.Schema.builder()
                .putAllAdditionalProperties(format.schema.toSdkProperties())
                .build()
            outputConfig.format(JsonOutputFormat.builder().schema(schema).build())
            hasOutputConfig = true
        }
        if (hasOutputConfig) builder.outputConfig(outputConfig.build())
        providerOptions.forEach { (name, value) -> builder.putAdditionalBodyProperty(name, JsonValue.from(value.toAny())) }
        return builder.build()
    }

    private fun Message.toPolyglot(request: ChatRequest): ChatResponse {
        val content = buildList {
            content().forEach { block ->
                when {
                    block.isText() -> add(ContentPart.Text(block.asText().text()))
                    block.isToolUse() -> {
                        val tool = block.asToolUse()
                        val input = tool._input().convert(JsonNode::class.java).toString()
                        add(ContentPart.ToolCall(tool.id(), tool.name(), JSON.parseToJsonElement(input).jsonObject))
                    }
                }
            }
        }
        val reasoning = content().filter { it.isThinking() }.joinToString("") { it.asThinking().thinking() }
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        return ChatResponse(
            request.target,
            content,
            Usage(
                inputTokens = usage().inputTokens() + usage().cacheReadInputTokens().orElse(0L) + usage().cacheCreationInputTokens().orElse(0L),
                outputTokens = usage().outputTokens(),
                cacheReadInputTokens = usage().cacheReadInputTokens().orElse(null),
                cacheWriteInputTokens = usage().cacheCreationInputTokens().orElse(null),
            ),
            stopReason().orElse(null)?.asString().toFinishReason(),
            if (request.responseFormat is ResponseFormat.JsonSchema && text.isNotBlank()) {
                runCatching { JSON.parseToJsonElement(text) }.getOrNull()
            } else null,
            mapOf("requestId" to id()),
            reasoning.takeIf { it.isNotEmpty() },
        )
    }

    private inline fun <T> sdkCall(block: () -> T): T = try {
        block()
    } catch (error: AnthropicException) {
        throw error.toProviderException()
    }

    private fun AnthropicException.toProviderException(): ProviderException {
        val status = (this as? AnthropicServiceException)?.statusCode()
        val kind = when (status) {
            400, 422 -> ProviderErrorKind.INVALID_REQUEST
            401 -> ProviderErrorKind.AUTHENTICATION
            403 -> ProviderErrorKind.PERMISSION
            404 -> ProviderErrorKind.NOT_FOUND
            408 -> ProviderErrorKind.TIMEOUT
            429 -> ProviderErrorKind.RATE_LIMIT
            in 500..599 -> ProviderErrorKind.PROVIDER_UNAVAILABLE
            null -> ProviderErrorKind.NETWORK
            else -> ProviderErrorKind.UNKNOWN
        }
        return ProviderException(ID, kind, message ?: "Anthropic request failed", status, cause = this)
    }

    companion object {
        private fun createClient(config: AnthropicConfig): AnthropicClient {
            val httpClient = config.httpClient
            if (httpClient == null) {
                return AnthropicOkHttpClient.builder()
                    .apiKey(config.apiKey)
                    .maxRetries(config.maxRetries)
                    .apply { config.baseUrl?.let(::baseUrl) }
                    .build()
            }

            // Keep SDK request mapping and retries while preserving the caller's TLS policy.
            val backend = AnthropicBackend.builder().apiKey(config.apiKey)
                .apply { config.baseUrl?.let(::baseUrl) }.build()
            return AnthropicClientImpl(
                ClientOptions.builder()
                    .baseUrl(backend.baseUrl())
                    .maxRetries(config.maxRetries)
                    .httpClient(AnthropicTransport(httpClient, backend))
                    .build(),
            )
        }

        val ID = ProviderId("anthropic")
        val CAPABILITIES = setOf(
            Capability.PROMPT_CACHING,
            Capability.CACHE_TTL,
            Capability.CACHE_BREAKPOINTS,
            Capability.CHAT,
            Capability.STREAMING,
            Capability.STREAMING_TOOL_CALLS,
            Capability.STRUCTURED_OUTPUT,
            Capability.TOOLS,
            Capability.REASONING,
        )
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun String?.toFinishReason() = when (this) {
    "end_turn", "stop_sequence" -> FinishReason.STOP
    "max_tokens", "model_context_window_exceeded" -> FinishReason.MAX_TOKENS
    "tool_use" -> FinishReason.TOOL_CALLS
    "refusal" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

private fun JsonObject.toSdkProperties() = mapValues { JsonValue.from(it.value.toAny()) }

private fun JsonElement.toAny(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toAny() }
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
}
