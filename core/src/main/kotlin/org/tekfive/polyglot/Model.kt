package org.tekfive.polyglot

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "Provider ID must not be blank" }
    }

    override fun toString(): String = value
}

data class ModelTarget(
    val provider: ProviderId,
    val model: String,
) {
    init {
        require(model.isNotBlank()) { "Model must not be blank" }
    }
}

enum class Capability {
    CHAT,
    STREAMING,
    STREAMING_TOOL_CALLS,
    STRUCTURED_OUTPUT,
    TOOLS,
    REASONING,
    IMAGE_INPUT,
    DOCUMENT_INPUT,
    AUDIO_INPUT,
    EMBEDDINGS,
    BATCH,
}

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

sealed interface ContentPart {
    data class Text(val text: String) : ContentPart
    data class Image(val source: ContentSource) : ContentPart
    data class Document(val source: ContentSource) : ContentPart
    data class Audio(val source: ContentSource) : ContentPart
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: JsonObject,
    ) : ContentPart

    data class ToolResult(
        val callId: String,
        val name: String,
        val result: JsonElement,
        val isError: Boolean = false,
    ) : ContentPart
}

sealed interface ContentSource {
    val mediaType: String

    data class Url(
        val url: String,
        override val mediaType: String,
    ) : ContentSource

    data class Base64(
        val data: String,
        override val mediaType: String,
    ) : ContentSource
}

data class Message(
    val role: MessageRole,
    val content: List<ContentPart>,
) {
    init {
        require(content.isNotEmpty()) { "Message content must not be empty" }
    }

    val text: String
        get() = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    companion object {
        fun system(text: String) = Message(MessageRole.SYSTEM, listOf(ContentPart.Text(text)))
        fun user(text: String) = Message(MessageRole.USER, listOf(ContentPart.Text(text)))
        fun assistant(text: String) = Message(MessageRole.ASSISTANT, listOf(ContentPart.Text(text)))
    }
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_-]+"))) {
            "Tool names may contain only letters, digits, underscores, and hyphens"
        }
    }
}

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String) : ToolChoice
}

sealed interface ResponseFormat {
    data object Text : ResponseFormat
    data class JsonSchema(
        val name: String,
        val schema: JsonObject,
        val description: String? = null,
        val strict: Boolean = true,
    ) : ResponseFormat
}

enum class ReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
}

data class GenerationOptions(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val stopSequences: List<String> = emptyList(),
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val reasoningEffort: ReasoningEffort? = null,
) {
    init {
        maxOutputTokens?.let { require(it > 0) { "maxOutputTokens must be positive" } }
        topP?.let { require(it in 0.0..1.0) { "topP must be between 0 and 1" } }
        topK?.let { require(it > 0) { "topK must be positive" } }
    }
}

data class ChatRequest(
    val target: ModelTarget,
    val messages: List<Message>,
    val fallbackTargets: List<ModelTarget> = emptyList(),
    val options: GenerationOptions = GenerationOptions(),
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice? = null,
    val responseFormat: ResponseFormat = ResponseFormat.Text,
    val providerOptions: JsonObject = JsonObject(emptyMap()),
) {
    init {
        require(messages.isNotEmpty()) { "Messages must not be empty" }
        require(tools.map { it.name }.toSet().size == tools.size) { "Tool names must be unique" }
        if (toolChoice is ToolChoice.Named) {
            require(tools.any { it.name == toolChoice.name }) { "Named tool choice must refer to a declared tool" }
        }
    }

    internal fun forTarget(target: ModelTarget) = copy(target = target, fallbackTargets = emptyList())
}

data class Usage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
) {
    val totalTokens: Long?
        get() = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
}

data class RateLimits(
    val requestLimit: Long? = null,
    val tokenLimit: Long? = null,
    val remainingRequests: Long? = null,
    val remainingTokens: Long? = null,
    val resetRequests: String? = null,
    val resetTokens: String? = null,
)

enum class FinishReason {
    STOP,
    MAX_TOKENS,
    TOOL_CALLS,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN,
}

data class ChatResponse(
    val target: ModelTarget,
    val content: List<ContentPart>,
    val usage: Usage = Usage(),
    val finishReason: FinishReason = FinishReason.UNKNOWN,
    val structuredOutput: JsonElement? = null,
    val providerMetadata: Map<String, String> = emptyMap(),
    val reasoning: String? = null,
    val rateLimits: RateLimits? = null,
) {
    val text: String
        get() = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }

    val toolCalls: List<ContentPart.ToolCall>
        get() = content.filterIsInstance<ContentPart.ToolCall>()
}

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class ReasoningDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : StreamEvent

    data class Completed(val response: ChatResponse) : StreamEvent
}
