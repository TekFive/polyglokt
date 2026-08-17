package org.tekfive.polyglot.openaicompatible

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.MessageRole
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.RateLimits
import org.tekfive.polyglot.ResponseFormat
import org.tekfive.polyglot.StreamEvent
import org.tekfive.polyglot.ToolChoice
import org.tekfive.polyglot.Usage
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Adapter for services that implement the OpenAI Chat Completions and Embeddings HTTP APIs.
 * Use the dedicated provider modules for OpenAI itself and other vendors with official SDKs.
 */
class OpenAiCompatibleProvider(
    override val id: ProviderId,
    apiKey: String,
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    extraHeaders: Map<String, String> = emptyMap(),
    override val capabilities: Set<Capability> = DEFAULT_CAPABILITIES,
    private val json: Json = DEFAULT_JSON,
) : ChatProvider, EmbeddingProvider {
    private val endpoint = baseUrl.trimEnd('/').removeSuffix("/v1").toHttpUrl()
    private val headers = Headers.Builder()
        .add("Authorization", "Bearer $apiKey")
        .apply { extraHeaders.forEach { (name, value) -> add(name, value) } }
        .build()

    override suspend fun complete(request: ChatRequest): ChatResponse {
        val response = execute("v1/chat/completions", chatBody(request, stream = false))
        return response.use { httpResponse ->
            val body = responseBody(httpResponse)
            parseChatResponse(request, json.parseToJsonElement(body).jsonObject).copy(
                rateLimits = httpResponse.toRateLimits(),
            )
        }
    }

    override fun stream(request: ChatRequest): Flow<StreamEvent> = flow {
        val response = execute("v1/chat/completions", chatBody(request, stream = true))
        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) throw httpError(httpResponse, httpResponse.body.string())
            val source = httpResponse.body.source()
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var finishReason = FinishReason.UNKNOWN
            var usage = Usage()
            val toolCalls = linkedMapOf<Int, MutableToolCall>()

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                val event = runCatching { json.parseToJsonElement(data).jsonObject }
                    .getOrElse { throw providerError(ProviderErrorKind.INVALID_RESPONSE, "Invalid SSE event", it) }
                event["error"]?.let { throw providerError(ProviderErrorKind.UNKNOWN, it.toString()) }
                event["usage"]?.takeUnless { it is JsonNull }?.jsonObject?.let { usage = parseUsage(it) }
                val choice = event["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.toFinishReason() ?: finishReason
                val delta = choice["delta"]?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull?.let {
                    text.append(it)
                    emit(StreamEvent.TextDelta(it))
                }
                delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let {
                    reasoning.append(it)
                    emit(StreamEvent.ReasoningDelta(it))
                }
                delta["tool_calls"]?.jsonArray?.forEach { part ->
                    val value = part.jsonObject
                    val index = value["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val function = value["function"]?.jsonObject
                    val accumulator = toolCalls.getOrPut(index) { MutableToolCall() }
                    value["id"]?.jsonPrimitive?.contentOrNull?.let { accumulator.id = it }
                    function?.get("name")?.jsonPrimitive?.contentOrNull?.let { accumulator.name = it }
                    val arguments = function?.get("arguments")?.jsonPrimitive?.contentOrNull
                    arguments?.let { accumulator.arguments.append(it) }
                    emit(StreamEvent.ToolCallDelta(index, accumulator.id, accumulator.name, arguments))
                }
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
                        rateLimits = httpResponse.toRateLimits(),
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun embed(request: EmbeddingRequest): EmbeddingResponse {
        val payload = buildJsonObject {
            put("model", request.target.model)
            putJsonArray("input") { request.input.forEach { add(JsonPrimitive(it)) } }
            request.dimensions?.let { put("dimensions", it) }
        }
        val response = execute("v1/embeddings", payload)
        return response.use { httpResponse ->
            val root = json.parseToJsonElement(responseBody(httpResponse)).jsonObject
            val embeddings = root["data"]?.jsonArray.orEmpty()
                .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0 }
                .map { item ->
                    item.jsonObject["embedding"]!!.jsonArray
                        .map { it.jsonPrimitive.doubleOrNull?.toFloat() ?: 0f }
                        .toFloatArray()
                }
            EmbeddingResponse(request.target, embeddings, parseUsage(root["usage"]?.jsonObject))
        }
    }

    private fun chatBody(request: ChatRequest, stream: Boolean) = buildJsonObject {
        put("model", request.target.model)
        put("stream", stream)
        if (stream) putJsonObject("stream_options") { put("include_usage", true) }
        putJsonArray("messages") { request.messages.forEach { add(message(it)) } }
        request.options.temperature?.let { put("temperature", it) }
        request.options.maxOutputTokens?.let { put("max_completion_tokens", it) }
        request.options.topP?.let { put("top_p", it) }
        request.options.presencePenalty?.let { put("presence_penalty", it) }
        request.options.frequencyPenalty?.let { put("frequency_penalty", it) }
        if (request.options.stopSequences.isNotEmpty()) {
            putJsonArray("stop") { request.options.stopSequences.forEach { add(JsonPrimitive(it)) } }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        }
                    })
                }
            }
        }
        request.toolChoice?.let { put("tool_choice", toolChoice(it)) }
        when (val format = request.responseFormat) {
            ResponseFormat.Text -> Unit
            is ResponseFormat.JsonSchema -> putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", format.name)
                    format.description?.let { put("description", it) }
                    put("strict", format.strict)
                    put("schema", format.schema)
                }
            }
        }
        request.providerOptions.forEach { (key, value) -> put(key, value) }
    }

    private fun message(message: Message): JsonObject = buildJsonObject {
        put("role", message.role.name.lowercase())
        val textOnly = message.content.all { it is ContentPart.Text }
        if (textOnly) {
            put("content", message.text)
            return@buildJsonObject
        }
        val toolCallParts = message.content.filterIsInstance<ContentPart.ToolCall>()
        val toolResult = message.content.singleOrNull() as? ContentPart.ToolResult
        if (toolResult != null) {
            put("role", "tool")
            put("tool_call_id", toolResult.callId)
            put("content", toolResult.result.toString())
            return@buildJsonObject
        }
        putJsonArray("content") {
            message.content.forEach { part ->
                when (part) {
                    is ContentPart.Text -> add(buildJsonObject { put("type", "text"); put("text", part.text) })
                    is ContentPart.Image -> add(imagePart(part.source))
                    is ContentPart.Audio -> add(audioPart(part.source))
                    is ContentPart.Document -> error("The OpenAI-compatible format has no portable document part")
                    is ContentPart.ToolCall, is ContentPart.ToolResult -> Unit
                }
            }
        }
        if (toolCallParts.isNotEmpty()) putJsonArray("tool_calls") {
            toolCallParts.forEach { call ->
                add(buildJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    putJsonObject("function") { put("name", call.name); put("arguments", call.arguments.toString()) }
                })
            }
        }
    }

    private fun imagePart(source: ContentSource) = buildJsonObject {
        put("type", "image_url")
        putJsonObject("image_url") { put("url", source.asUrl()) }
    }

    private fun audioPart(source: ContentSource) = buildJsonObject {
        require(source is ContentSource.Base64) { "Audio input must be base64 encoded" }
        put("type", "input_audio")
        putJsonObject("input_audio") {
            put("data", source.data)
            put("format", source.mediaType.substringAfter('/'))
        }
    }

    private fun toolChoice(choice: ToolChoice): JsonElement = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Named -> buildJsonObject {
            put("type", "function")
            putJsonObject("function") { put("name", choice.name) }
        }
    }

    private fun parseChatResponse(request: ChatRequest, root: JsonObject): ChatResponse {
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw providerError(ProviderErrorKind.INVALID_RESPONSE, "Response contained no choices")
        val message = choice["message"]?.jsonObject
            ?: throw providerError(ProviderErrorKind.INVALID_RESPONSE, "Choice contained no message")
        val content = buildList {
            message["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { add(ContentPart.Text(it)) }
            message["tool_calls"]?.jsonArray?.forEach { item ->
                val call = item.jsonObject
                val function = call["function"]!!.jsonObject
                val arguments = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
                add(
                    ContentPart.ToolCall(
                        call["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        function["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        parseArguments(arguments),
                    ),
                )
            }
        }
        val text = content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text }
        val structured = if (request.responseFormat is ResponseFormat.JsonSchema && text.isNotBlank()) {
            runCatching { json.parseToJsonElement(text) }.getOrNull()
        } else null
        return ChatResponse(
            target = request.target,
            content = content,
            usage = parseUsage(root["usage"]?.jsonObject),
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull.toFinishReason(),
            structuredOutput = structured,
            providerMetadata = root["id"]?.jsonPrimitive?.contentOrNull?.let { mapOf("requestId" to it) }.orEmpty(),
            reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private suspend fun execute(path: String, payload: JsonObject): Response {
        val url = endpoint.newBuilder().addPathSegments(path).build()
        val request = Request.Builder()
            .url(url)
            .headers(headers)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw providerError(ProviderErrorKind.NETWORK, error.message ?: "Network request failed", error)
        }
    }

    private fun responseBody(response: Response): String {
        val body = response.body.string()
        if (!response.isSuccessful) throw httpError(response, body)
        return body
    }

    private fun httpError(response: Response, body: String): ProviderException {
        val kind = when (response.code) {
            400, 422 -> ProviderErrorKind.INVALID_REQUEST
            401 -> ProviderErrorKind.AUTHENTICATION
            403 -> ProviderErrorKind.PERMISSION
            404 -> ProviderErrorKind.NOT_FOUND
            408 -> ProviderErrorKind.TIMEOUT
            429 -> ProviderErrorKind.RATE_LIMIT
            in 500..599 -> ProviderErrorKind.PROVIDER_UNAVAILABLE
            else -> ProviderErrorKind.UNKNOWN
        }
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: "Provider returned HTTP ${response.code}"
        return ProviderException(id, kind, message, response.code, response.header("x-request-id"))
    }

    private fun providerError(kind: ProviderErrorKind, message: String, cause: Throwable? = null) =
        ProviderException(id, kind, message, cause = cause)

    private fun parseUsage(value: JsonObject?): Usage = Usage(
        inputTokens = value?.get("prompt_tokens")?.jsonPrimitive?.longOrNull,
        outputTokens = value?.get("completion_tokens")?.jsonPrimitive?.longOrNull,
        reasoningTokens = value?.get("completion_tokens_details")?.jsonObject
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull,
    )

    private fun Response.toRateLimits(): RateLimits? {
        val limits = RateLimits(
            requestLimit = header("x-ratelimit-limit-requests")?.toLongOrNull(),
            tokenLimit = header("x-ratelimit-limit-tokens")?.toLongOrNull(),
            remainingRequests = header("x-ratelimit-remaining-requests")?.toLongOrNull(),
            remainingTokens = header("x-ratelimit-remaining-tokens")?.toLongOrNull(),
            resetRequests = header("x-ratelimit-reset-requests"),
            resetTokens = header("x-ratelimit-reset-tokens"),
        )
        return limits.takeUnless { value ->
            value.requestLimit == null && value.tokenLimit == null &&
                value.remainingRequests == null && value.remainingTokens == null &&
                value.resetRequests == null && value.resetTokens == null
        }
    }

    private fun parseArguments(value: String): JsonObject = runCatching {
        json.parseToJsonElement(value).jsonObject
    }.getOrElse { throw providerError(ProviderErrorKind.INVALID_RESPONSE, "Tool arguments were not a JSON object", it) }

    private data class MutableToolCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun toContentPart() = ContentPart.ToolCall(
            requireNotNull(id) { "Streaming tool call had no ID" },
            requireNotNull(name) { "Streaming tool call had no name" },
            Json.parseToJsonElement(arguments.toString()).jsonObject,
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
        val DEFAULT_CAPABILITIES = setOf(
            Capability.CHAT,
            Capability.STREAMING,
            Capability.STREAMING_TOOL_CALLS,
            Capability.STRUCTURED_OUTPUT,
            Capability.TOOLS,
            Capability.IMAGE_INPUT,
            Capability.AUDIO_INPUT,
            Capability.EMBEDDINGS,
        )
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}

private fun ContentSource.asUrl(): String = when (this) {
    is ContentSource.Url -> url
    is ContentSource.Base64 -> "data:$mediaType;base64,$data"
}

private fun String?.toFinishReason(): FinishReason = when (this) {
    "stop" -> FinishReason.STOP
    "length" -> FinishReason.MAX_TOKENS
    "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
    "content_filter" -> FinishReason.CONTENT_FILTER
    null -> FinishReason.UNKNOWN
    else -> FinishReason.UNKNOWN
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
