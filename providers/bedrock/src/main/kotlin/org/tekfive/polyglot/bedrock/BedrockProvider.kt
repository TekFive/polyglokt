package org.tekfive.polyglot.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.AnyToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.AutoToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.ContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ConversationRole
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseResponse
import aws.sdk.kotlin.services.bedrockruntime.model.ConverseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.InferenceConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.Message
import aws.sdk.kotlin.services.bedrockruntime.model.SpecificToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.SystemContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.Tool
import aws.sdk.kotlin.services.bedrockruntime.model.ToolChoice as BedrockToolChoice
import aws.sdk.kotlin.services.bedrockruntime.model.ToolConfiguration
import aws.sdk.kotlin.services.bedrockruntime.model.ToolInputSchema
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultContentBlock
import aws.sdk.kotlin.services.bedrockruntime.model.ToolResultStatus
import aws.sdk.kotlin.services.bedrockruntime.model.ToolSpecification
import aws.sdk.kotlin.services.bedrockruntime.model.ToolUseBlock
import aws.smithy.kotlin.runtime.ClientException
import aws.smithy.kotlin.runtime.SdkBaseException
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.content.Document
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
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
import org.tekfive.polyglot.FinishReason
import org.tekfive.polyglot.MessageRole
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.StreamEvent
import org.tekfive.polyglot.ToolChoice
import org.tekfive.polyglot.Usage

class BedrockConfig(
    internal val region: String,
) {
    init {
        require(region.isNotBlank()) { "region must not be blank" }
    }
}

class BedrockProvider private constructor(
    private val client: BedrockRuntimeClient,
) : ChatProvider, AutoCloseable {
    constructor(config: BedrockConfig) : this(
        BedrockRuntimeClient { region = config.region },
    )

    override val id = ID
    override val capabilities = CAPABILITIES

    override suspend fun complete(request: ChatRequest): ChatResponse = try {
        client.converse(request.toSdk()).toPolyglot(request)
    } catch (error: SdkBaseException) {
        throw error.toProviderException()
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = linkedMapOf<Int, MutableToolCall>()
        var usage = Usage()
        var finishReason = FinishReason.UNKNOWN
        try {
            client.converseStream(request.toStreamSdk()) { response ->
                response.stream?.collect { event ->
                    event.asContentBlockStartOrNull()?.let { startEvent ->
                        startEvent.start?.asToolUseOrNull()?.let { tool ->
                            val state = toolCalls.getOrPut(startEvent.contentBlockIndex) { MutableToolCall() }
                            state.id = tool.toolUseId
                            state.name = tool.name
                            emit(StreamEvent.ToolCallDelta(startEvent.contentBlockIndex, tool.toolUseId, tool.name))
                        }
                    }
                    event.asContentBlockDeltaOrNull()?.let { deltaEvent ->
                        val delta = deltaEvent.delta ?: return@let
                        delta.asTextOrNull()?.let {
                            text.append(it)
                            emit(StreamEvent.TextDelta(it))
                        }
                        delta.asReasoningContentOrNull()?.asTextOrNull()?.let {
                            reasoning.append(it)
                            emit(StreamEvent.ReasoningDelta(it))
                        }
                        delta.asToolUseOrNull()?.input?.let {
                            val state = toolCalls.getOrPut(deltaEvent.contentBlockIndex) { MutableToolCall() }
                            state.arguments.append(it)
                            emit(StreamEvent.ToolCallDelta(deltaEvent.contentBlockIndex, argumentsDelta = it))
                        }
                    }
                    event.asMessageStopOrNull()?.let { finishReason = it.stopReason.value.toFinishReason() }
                    event.asMetadataOrNull()?.usage?.let {
                        usage = Usage(it.inputTokens.toLong(), it.outputTokens.toLong())
                    }
                }
            }
        } catch (error: SdkBaseException) {
            throw error.toProviderException()
        }

        val content = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text.toString()))
            toolCalls.values.forEach { add(it.toContentPart()) }
        }
        emit(
            StreamEvent.Completed(
                ChatResponse(
                    request.target,
                    content,
                    usage,
                    finishReason,
                    reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
                ),
            ),
        )
    }

    override fun close() = client.close()

    private fun ChatRequest.toSdk() = ConverseRequest {
        modelId = target.model
        system = this@toSdk.messages.filter { it.role == MessageRole.SYSTEM }
            .map { SystemContentBlock.Text(it.text) }
        this.messages = this@toSdk.messages.filter { it.role != MessageRole.SYSTEM }.map { message ->
            Message {
                role = if (message.role == MessageRole.ASSISTANT) ConversationRole.Assistant else ConversationRole.User
                content = message.content.map { it.toSdk() }
            }
        }
        inferenceConfig = InferenceConfiguration {
            maxTokens = options.maxOutputTokens
            temperature = options.temperature?.toFloat()
            topP = options.topP?.toFloat()
            stopSequences = options.stopSequences
        }
        if (tools.isNotEmpty()) toolConfig = ToolConfiguration {
            this.tools = this@toSdk.tools.map { definition ->
                Tool.ToolSpec(
                    ToolSpecification {
                        name = definition.name
                        description = definition.description
                        strict = true
                        inputSchema = ToolInputSchema.Json(definition.inputSchema.toDocument())
                    },
                )
            }
            toolChoice = when (val choice = this@toSdk.toolChoice) {
                null, ToolChoice.Auto -> BedrockToolChoice.Auto(AutoToolChoice {})
                ToolChoice.Required -> BedrockToolChoice.Any(AnyToolChoice {})
                ToolChoice.None -> error("Bedrock Converse does not define a portable 'none' tool choice")
                is ToolChoice.Named -> BedrockToolChoice.Tool(SpecificToolChoice { name = choice.name })
            }
        }
        if (providerOptions.isNotEmpty()) additionalModelRequestFields = providerOptions.toDocument()
    }

    private fun ChatRequest.toStreamSdk(): ConverseStreamRequest {
        val request = toSdk()
        return ConverseStreamRequest {
            modelId = request.modelId
            system = request.system
            messages = request.messages
            inferenceConfig = request.inferenceConfig
            toolConfig = request.toolConfig
            additionalModelRequestFields = request.additionalModelRequestFields
        }
    }

    private fun ContentPart.toSdk(): ContentBlock = when (this) {
        is ContentPart.Text -> ContentBlock.Text(text)
        is ContentPart.ToolCall -> ContentBlock.ToolUse(
            ToolUseBlock {
                toolUseId = id
                name = this@toSdk.name
                input = arguments.toDocument()
            },
        )
        is ContentPart.ToolResult -> ContentBlock.ToolResult(
            ToolResultBlock {
                toolUseId = callId
                status = if (isError) ToolResultStatus.Error else ToolResultStatus.Success
                content = listOf(ToolResultContentBlock.Json(result.toDocument()))
            },
        )
        else -> error("Bedrock provider currently accepts text and tool content")
    }

    private fun ConverseResponse.toPolyglot(request: ChatRequest): ChatResponse {
        val message = output?.asMessageOrNull()
            ?: throw ProviderException(ID, ProviderErrorKind.INVALID_RESPONSE, "Bedrock returned no message")
        val content = message.content.mapNotNull { block ->
            block.asTextOrNull()?.let(ContentPart::Text)
                ?: block.asToolUseOrNull()?.let {
                    ContentPart.ToolCall(
                        it.toolUseId,
                        it.name,
                        requireNotNull(it.input) { "Bedrock tool call had no input" }.toJsonElement() as JsonObject,
                    )
                }
        }
        val reasoning = message.content.mapNotNull { block ->
            block.asReasoningContentOrNull()?.asReasoningTextOrNull()?.text
        }.joinToString("")
        return ChatResponse(
            request.target,
            content,
            usage?.let { Usage(it.inputTokens.toLong(), it.outputTokens.toLong()) } ?: Usage(),
            stopReason.value.toFinishReason(),
            reasoning = reasoning.takeIf { it.isNotEmpty() },
        )
    }

    private fun SdkBaseException.toProviderException(): ProviderException {
        val service = this as? ServiceException
        val metadata = sdkErrorMetadata
        val kind = when {
            metadata.isThrottling -> ProviderErrorKind.RATE_LIMIT
            this is ClientException -> ProviderErrorKind.NETWORK
            metadata.isRetryable -> ProviderErrorKind.PROVIDER_UNAVAILABLE
            service?.sdkErrorMetadata?.errorCode?.contains("AccessDenied", ignoreCase = true) == true -> ProviderErrorKind.PERMISSION
            service?.sdkErrorMetadata?.errorCode?.contains("Validation", ignoreCase = true) == true -> ProviderErrorKind.INVALID_REQUEST
            else -> ProviderErrorKind.UNKNOWN
        }
        return ProviderException(
            ID,
            kind,
            message ?: "Bedrock request failed",
            requestId = service?.sdkErrorMetadata?.requestId,
            cause = this,
        )
    }

    private inner class MutableToolCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun toContentPart() = ContentPart.ToolCall(
            requireNotNull(id) { "Streaming Bedrock tool call had no ID" },
            requireNotNull(name) { "Streaming Bedrock tool call had no name" },
            Json.parseToJsonElement(arguments.toString()).let {
                it as? JsonObject ?: throw ProviderException(
                    ID,
                    ProviderErrorKind.INVALID_RESPONSE,
                    "Streaming Bedrock tool arguments were not a JSON object",
                )
            },
        )
    }

    companion object {
        val ID = ProviderId("bedrock")
        val CAPABILITIES = setOf(
            Capability.CHAT,
            Capability.STREAMING,
            Capability.STREAMING_TOOL_CALLS,
            Capability.TOOLS,
        )
    }
}

private fun String?.toFinishReason() = when (this) {
    "end_turn", "stop_sequence" -> FinishReason.STOP
    "max_tokens", "model_context_window_exceeded" -> FinishReason.MAX_TOKENS
    "tool_use" -> FinishReason.TOOL_CALLS
    "content_filtered", "guardrail_intervened" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

private fun JsonElement.toDocument(): Document = when (this) {
    JsonNull -> Document("null")
    is JsonObject -> Document(mapValues { it.value.toDocument() })
    is JsonArray -> Document(map { it.toDocument() })
    is JsonPrimitive -> booleanOrNull?.let(::Document)
        ?: longOrNull?.let(::Document)
        ?: doubleOrNull?.let(::Document)
        ?: Document(contentOrNull.orEmpty())
}

private fun Document.toJsonElement(): JsonElement = when {
    asMapOrNull() != null -> JsonObject(asMap().mapValues { requireNotNull(it.value).toJsonElement() })
    asListOrNull() != null -> JsonArray(asList().map { requireNotNull(it).toJsonElement() })
    asBooleanOrNull() != null -> JsonPrimitive(asBoolean())
    asLongOrNull() != null -> JsonPrimitive(asLong())
    asDoubleOrNull() != null -> JsonPrimitive(asDouble())
    else -> JsonPrimitive(asStringOrNull().orEmpty())
}
