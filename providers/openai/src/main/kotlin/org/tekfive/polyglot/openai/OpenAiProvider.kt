package org.tekfive.polyglot.openai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.errors.OpenAIException
import com.openai.errors.OpenAIServiceException
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.openai.models.ReasoningEffort as OpenAiReasoningEffort
import com.openai.models.ResponseFormatJsonSchema
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
import com.openai.models.chat.completions.ChatCompletionNamedToolChoice
import com.openai.models.chat.completions.ChatCompletionToolChoiceOption
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import com.openai.models.embeddings.EmbeddingCreateParams
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
import org.tekfive.polyglot.Usage

class OpenAiConfig(
    internal val apiKey: String,
    internal val baseUrl: String? = null,
    internal val organization: String? = null,
    internal val project: String? = null,
    internal val maxRetries: Int = 2,
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }

    override fun toString() = "OpenAiConfig(apiKey=<redacted>, baseUrl=$baseUrl, organization=$organization, project=$project, maxRetries=$maxRetries)"
}

class OpenAiProvider private constructor(
    private val client: OpenAIClient,
) : ChatProvider, EmbeddingProvider, AutoCloseable {
    constructor(config: OpenAiConfig) : this(
        OpenAIOkHttpClient.builder()
            .apiKey(config.apiKey)
            .maxRetries(config.maxRetries)
            .apply {
                config.baseUrl?.let(::baseUrl)
                config.organization?.let(::organization)
                config.project?.let(::project)
            }
            .build(),
    )

    override val id = ID
    override val capabilities = CAPABILITIES

    override suspend fun complete(request: ChatRequest): ChatResponse = withContext(Dispatchers.IO) {
        sdkCall { client.chat().completions().create(request.toSdk()) }.toPolyglot(request)
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val text = StringBuilder()
        val toolCalls = linkedMapOf<Int, MutableToolCall>()
        var usage = Usage()
        var finishReason = FinishReason.UNKNOWN

        sdkCall { client.chat().completions().createStreaming(request.toSdk()) }.use { response ->
            val iterator = response.stream().iterator()
            while (iterator.hasNext()) {
                val chunk = iterator.next()
                chunk.usage().orElse(null)?.let { usage = it.toUsage() }
                for (choice in chunk.choices()) {
                    choice.finishReason().orElse(null)?.let { finishReason = it.asString().toFinishReason() }
                    val delta = choice.delta()
                    delta.content().orElse(null)?.let { content ->
                        text.append(content)
                        emit(StreamEvent.TextDelta(content))
                    }
                    delta.toolCalls().orElse(null)?.let { calls ->
                        for (call in calls) {
                            val index = call.index().toInt()
                            val accumulator = toolCalls.getOrPut(index) { MutableToolCall() }
                            call.id().orElse(null)?.let { accumulator.id = it }
                            val function = call.function().orElse(null)
                            function?.name()?.orElse(null)?.let { accumulator.name = it }
                            val arguments = function?.arguments()?.orElse(null)
                            arguments?.let { accumulator.arguments.append(it) }
                            emit(StreamEvent.ToolCallDelta(index, accumulator.id, accumulator.name, arguments))
                        }
                    }
                }
            }
        }

        emit(
            StreamEvent.Completed(
                ChatResponse(
                    request.target,
                    buildList {
                        if (text.isNotEmpty()) add(ContentPart.Text(text.toString()))
                        toolCalls.values.forEach { add(it.toContentPart()) }
                    },
                    usage,
                    finishReason,
                ),
            ),
        )
    }.flowOn(Dispatchers.IO)

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse = withContext(Dispatchers.IO) {
        val params = EmbeddingCreateParams.builder()
            .model(request.target.model)
            .inputOfArrayOfStrings(request.input)
            .apply { request.dimensions?.let { dimensions(it.toLong()) } }
            .build()
        val response = sdkCall { client.embeddings().create(params) }
        EmbeddingResponse(
            request.target,
            response.data().sortedBy { it.index() }.map { it.embedding().toFloatArray() },
            Usage(inputTokens = response.usage().promptTokens()),
        )
    }

    override fun close() = client.close()

    private fun ChatRequest.toSdk(): ChatCompletionCreateParams {
        val builder = ChatCompletionCreateParams.builder().model(target.model)
        messages.forEach { message ->
            when (message.role) {
                MessageRole.SYSTEM -> builder.addSystemMessage(message.textOnly())
                MessageRole.USER -> builder.addUserMessage(message.textOnly())
                MessageRole.ASSISTANT -> {
                    val sdkMessage = ChatCompletionAssistantMessageParam.builder()
                    message.text.takeIf { it.isNotEmpty() }?.let(sdkMessage::content)
                    message.content.filterIsInstance<ContentPart.ToolCall>().forEach {
                        sdkMessage.addToolCall(it.toSdk())
                    }
                    builder.addMessage(sdkMessage.build())
                }
                MessageRole.TOOL -> message.content.filterIsInstance<ContentPart.ToolResult>().forEach {
                    builder.addMessage(
                        ChatCompletionToolMessageParam.builder()
                            .toolCallId(it.callId)
                            .content(it.result.toString())
                            .build(),
                    )
                }
            }
        }
        options.temperature?.let(builder::temperature)
        options.maxOutputTokens?.let { builder.maxCompletionTokens(it.toLong()) }
        options.topP?.let(builder::topP)
        options.presencePenalty?.let(builder::presencePenalty)
        options.frequencyPenalty?.let(builder::frequencyPenalty)
        if (options.stopSequences.isNotEmpty()) builder.stopOfStrings(options.stopSequences)
        options.reasoningEffort?.takeUnless { it == ReasoningEffort.NONE }?.let {
            builder.reasoningEffort(OpenAiReasoningEffort.of(it.name.lowercase()))
        }
        tools.forEach { tool ->
            val parameters = FunctionParameters.builder()
                .putAllAdditionalProperties(tool.inputSchema.mapValues { JsonValue.from(it.value.toAny()) })
                .build()
            builder.addFunctionTool(
                FunctionDefinition.builder()
                    .name(tool.name)
                    .description(tool.description)
                    .parameters(parameters)
                    .strict(true)
                    .build(),
            )
        }
        toolChoice?.let {
            when (it) {
                ToolChoice.Auto -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.AUTO)
                ToolChoice.None -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.NONE)
                ToolChoice.Required -> builder.toolChoice(ChatCompletionToolChoiceOption.Auto.REQUIRED)
                is ToolChoice.Named -> builder.toolChoice(
                    ChatCompletionNamedToolChoice.builder()
                        .function(ChatCompletionNamedToolChoice.Function.builder().name(it.name).build())
                        .build(),
                )
            }
        }
        (responseFormat as? ResponseFormat.JsonSchema)?.let { format ->
            val schema = ResponseFormatJsonSchema.JsonSchema.Schema.builder()
                .putAllAdditionalProperties(format.schema.mapValues { JsonValue.from(it.value.toAny()) })
                .build()
            builder.jsonSchemaResponseFormat(
                ResponseFormatJsonSchema.JsonSchema.builder()
                    .name(format.name)
                    .apply { format.description?.let(::description) }
                    .schema(schema)
                    .strict(format.strict)
                    .build(),
            )
        }
        providerOptions.forEach { (name, value) -> builder.putAdditionalBodyProperty(name, JsonValue.from(value.toAny())) }
        return builder.build()
    }

    private fun ChatCompletion.toPolyglot(request: ChatRequest): ChatResponse {
        val choice = choices().firstOrNull()
            ?: throw providerError(ProviderErrorKind.INVALID_RESPONSE, "OpenAI returned no choices")
        val message = choice.message()
        val content = buildList {
            message.content().orElse(null)?.takeIf { it.isNotEmpty() }?.let { add(ContentPart.Text(it)) }
            message.toolCalls().orElse(emptyList()).forEach { tool ->
                if (tool.isFunction()) add(tool.asFunction().toContentPart())
            }
        }
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        return ChatResponse(
            request.target,
            content,
            usage().orElse(null)?.toUsage() ?: Usage(),
            choice.finishReason().asString().toFinishReason(),
            if (request.responseFormat is ResponseFormat.JsonSchema && text.isNotBlank()) {
                runCatching { JSON.parseToJsonElement(text) }.getOrNull()
            } else null,
            mapOf("requestId" to id()),
        )
    }

    private fun ChatCompletionMessageFunctionToolCall.toContentPart() = ContentPart.ToolCall(
        id(),
        function().name(),
        parseArguments(function().arguments()),
    )

    private fun ContentPart.ToolCall.toSdk() = ChatCompletionMessageFunctionToolCall.builder()
        .id(id)
        .function(
            ChatCompletionMessageFunctionToolCall.Function.builder()
                .name(name)
                .arguments(arguments.toString())
                .build(),
        )
        .build()

    private fun org.tekfive.polyglot.Message.textOnly(): String {
        require(content.all { it is ContentPart.Text }) { "OpenAI provider currently accepts text-only system and user messages" }
        return text
    }

    private inner class MutableToolCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun toContentPart() = ContentPart.ToolCall(
            requireNotNull(id) { "Streaming tool call had no ID" },
            requireNotNull(name) { "Streaming tool call had no name" },
            parseArguments(arguments.toString()),
        )
    }

    private fun parseArguments(value: String): JsonObject = runCatching { JSON.parseToJsonElement(value).jsonObject }
        .getOrElse { throw providerError(ProviderErrorKind.INVALID_RESPONSE, "Tool arguments were not a JSON object", it) }

    private inline fun <T> sdkCall(block: () -> T): T = try {
        block()
    } catch (error: OpenAIException) {
        throw error.toProviderException()
    }

    private fun OpenAIException.toProviderException(): ProviderException {
        val serviceError = this as? OpenAIServiceException
        val status = serviceError?.statusCode()
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
        return ProviderException(ID, kind, message ?: "OpenAI request failed", status, cause = this)
    }

    private fun providerError(kind: ProviderErrorKind, message: String, cause: Throwable? = null) =
        ProviderException(ID, kind, message, cause = cause)

    companion object {
        val ID = ProviderId("openai")
        val CAPABILITIES = setOf(
            Capability.CHAT,
            Capability.STREAMING,
            Capability.STREAMING_TOOL_CALLS,
            Capability.STRUCTURED_OUTPUT,
            Capability.TOOLS,
            Capability.REASONING,
            Capability.EMBEDDINGS,
        )
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun com.openai.models.completions.CompletionUsage.toUsage() = Usage(
    promptTokens(),
    completionTokens(),
    completionTokensDetails().orElse(null)?.reasoningTokens()?.orElse(null),
)

private fun String.toFinishReason() = when (this) {
    "stop" -> FinishReason.STOP
    "length" -> FinishReason.MAX_TOKENS
    "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
    "content_filter" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

private fun JsonElement.toAny(): Any? = when (this) {
    JsonNull -> null
    is JsonObject -> mapValues { it.value.toAny() }
    is JsonArray -> map { it.toAny() }
    is JsonPrimitive -> booleanOrNull ?: longOrNull ?: doubleOrNull ?: contentOrNull
}
