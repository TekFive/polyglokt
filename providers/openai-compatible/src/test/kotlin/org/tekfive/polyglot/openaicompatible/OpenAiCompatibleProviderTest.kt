package org.tekfive.polyglot.openaicompatible

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.EmbeddingRequest
import org.tekfive.polyglot.FinishReason
import org.tekfive.polyglot.GenerationOptions
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.PolyglotClient
import org.tekfive.polyglot.PromptCaching
import org.tekfive.polyglot.UnsupportedCapabilityException
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.ReasoningEffort
import org.tekfive.polyglot.ResponseFormat
import org.tekfive.polyglot.StreamEvent
import org.tekfive.polyglot.ToolDefinition
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiCompatibleProviderTest {
    private val server = MockWebServer()
    private val providerId = ProviderId("test")
    private val provider by lazy {
        OpenAiCompatibleProvider(providerId, "secret", server.url("/").toString())
    }

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `json output asks for a JSON object and parses the answer`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"id":"req-1","choices":[{"message":{"content":"{\"title\":\"Policy\"}"},"finish_reason":"stop"}]}""",
            ).build(),
        )

        val response = provider.complete(
            ChatRequest(
                ModelTarget(providerId, "test-model"),
                listOf(Message.user("Answer in JSON.")),
                responseFormat = ResponseFormat.Json,
            ),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()).jsonObject
        assertEquals("json_object", body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("Policy", response.structuredOutput?.jsonObject?.get("title")?.jsonPrimitive?.content)
    }

    @Test
    fun `maps chat response and sends tools`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"id":"req-1","choices":[{"message":{"content":"hello"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}""",
            ).build(),
        )
        val target = ModelTarget(providerId, "test-model")
        val response = provider.complete(
            ChatRequest(
                target,
                listOf(Message.user("hi")),
                tools = listOf(ToolDefinition("weather", "Get weather", JsonObject(mapOf("type" to JsonPrimitive("object"))))),
            ),
        )

        assertEquals("hello", response.text)
        assertEquals(3, response.usage.inputTokens)
        assertEquals("req-1", response.providerMetadata["requestId"])
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer secret", recorded.headers["Authorization"])
        assertTrue(recorded.body?.utf8().orEmpty().contains("\"weather\""))
    }

    @Test
    fun `streams deltas and completion`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """data: {"choices":[{"delta":{"content":"hel"},"finish_reason":null}]}

data: {"choices":[{"delta":{"content":"lo"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2}}

data: [DONE]

""",
            ).setHeader("Content-Type", "text/event-stream").build(),
        )

        val events = provider.stream(
            ChatRequest(ModelTarget(providerId, "test-model"), listOf(Message.user("hi"))),
        ).toList()

        assertEquals(listOf("hel", "lo"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        assertEquals("hello", assertIs<StreamEvent.Completed>(events.last()).response.text)
    }

    @Test
    fun `maps embeddings and provider errors`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"data":[{"index":0,"embedding":[0.1,0.2]}],"usage":{"prompt_tokens":2,"total_tokens":2}}""",
            ).build(),
        )
        val target = ModelTarget(providerId, "embed-model")
        val response = provider.embed(EmbeddingRequest(target, listOf("hello")))
        assertTrue(response.embeddings.single().contentEquals(floatArrayOf(0.1f, 0.2f)))

        server.enqueue(
            MockResponse.Builder().code(429).body("""{"error":{"message":"slow down"}}""").build(),
        )
        val error = assertFailsWith<ProviderException> {
            provider.complete(ChatRequest(ModelTarget(providerId, "model"), listOf(Message.user("hi"))))
        }
        assertEquals(ProviderErrorKind.RATE_LIMIT, error.kind)
        assertTrue(error.retryable)
    }

    @ParameterizedTest
    @MethodSource("reasoningFields")
    fun `streams reasoning aliases without losing or duplicating tokens`(fields: String) = runTest {
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(
                """data: {"choices":[{"delta":{"role":"assistant","content":null,"reasoning":null,"reasoning_content":null}}]}

data: {"choices":[{"delta":{$fields,"content":null}}]}

data: {"choices":[{"delta":{${fields.replace("Think", " carefully")},"content":"Answer"}}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":5,"completion_tokens_details":{"reasoning_tokens":2}}}

data: [DONE]

""",
            ).build(),
        )

        val events = PolyglotClient(listOf(provider)).stream(
            ChatRequest(ModelTarget(providerId, "reasoning-model"), listOf(Message.user("hi"))),
        ).toList()

        assertEquals(listOf("Think", " carefully"), events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
        assertEquals(listOf("Answer"), events.filterIsInstance<StreamEvent.TextDelta>().map { it.text })
        val response = assertIs<StreamEvent.Completed>(events.last()).response
        assertEquals("Think carefully", response.reasoning)
        assertEquals("Answer", response.text)
        assertEquals(FinishReason.STOP, response.finishReason)
        assertEquals(2, response.usage.reasoningTokens)
    }

    @ParameterizedTest
    @MethodSource("reasoningFields")
    fun `maps reasoning aliases in complete responses`(fields: String) = runTest {
        server.enqueue(
            MockResponse.Builder().body(
                """{"choices":[{"message":{$fields,"content":"Answer"},"finish_reason":"stop"}]}""",
            ).build(),
        )

        val response = PolyglotClient(listOf(provider)).complete(
            ChatRequest(ModelTarget(providerId, "reasoning-model"), listOf(Message.user("hi"))),
        )

        assertEquals("Think", response.reasoning)
        assertEquals("Answer", response.text)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `sends xhigh effort and preserves reasoning in both response modes`(streaming: Boolean) = runTest {
        val body = if (streaming) {
            """data: {"choices":[{"delta":{"reasoning":"Think","content":"Answer"},"finish_reason":"stop"}]}

data: [DONE]

"""
        } else {
            """{"choices":[{"message":{"reasoning":"Think","content":"Answer"},"finish_reason":"stop"}]}"""
        }
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", if (streaming) "text/event-stream" else "application/json")
                .body(body)
                .build(),
        )
        val reasoningProvider = OpenAiCompatibleProvider(
            providerId,
            "secret",
            server.url("/").toString(),
            capabilities = OpenAiCompatibleProvider.DEFAULT_CAPABILITIES + Capability.REASONING,
        )
        val client = PolyglotClient(listOf(reasoningProvider))
        val request = ChatRequest(
            ModelTarget(providerId, "reasoning-model"),
            listOf(Message.user("hi")),
            options = GenerationOptions(reasoningEffort = ReasoningEffort.XHIGH),
        )

        val response = if (streaming) {
            val events = client.stream(request).toList()
            assertEquals(listOf("Think"), events.filterIsInstance<StreamEvent.ReasoningDelta>().map { it.text })
            assertIs<StreamEvent.Completed>(events.last()).response
        } else {
            client.complete(request)
        }

        assertEquals("Think", response.reasoning)
        assertEquals("Answer", response.text)
        val payload = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals(JsonPrimitive("xhigh"), payload["reasoning_effort"])
        assertEquals(JsonPrimitive(streaming), payload["stream"])
    }

    @Test
    fun `cache support is opt in and maps keys breakpoints and cache hits`() = runTest {
        val request = ChatRequest(ModelTarget(providerId, "model"), listOf(Message.system("context"), Message.user("hi")),
            caching = PromptCaching(key = "session", messageBreakpoints = setOf(0)))
        assertFailsWith<UnsupportedCapabilityException> { provider.complete(request) }
        assertEquals(0, server.requestCount)
        val cachedProvider = OpenAiCompatibleProvider(providerId, "secret", server.url("/").toString(),
            capabilities = OpenAiCompatibleProvider.DEFAULT_CAPABILITIES + setOf(Capability.PROMPT_CACHING, Capability.CACHE_KEY, Capability.CACHE_BREAKPOINTS))
        val usage = "\"usage\":{\"prompt_tokens\":110,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":100}}"
        for (streaming in listOf(false, true)) {
            val response = if (streaming) "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],$usage}\n\ndata: [DONE]\n\n"
                else "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}],$usage}"
            server.enqueue(MockResponse.Builder().body(response).build())
            val result = if (streaming) assertIs<StreamEvent.Completed>(cachedProvider.stream(request).toList().last()).response else cachedProvider.complete(request)
            assertEquals(110, result.usage.inputTokens)
            assertEquals(100, result.usage.cacheReadInputTokens)
            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("session", body["prompt_cache_key"]!!.jsonPrimitive.content)
            assertEquals("explicit", body["prompt_cache_options"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertTrue("prompt_cache_breakpoint" in body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "{}", "{\"cached_tokens\":null}"])
    fun `optional cache details remain unreported`(details: String) = runTest {
        server.enqueue(MockResponse.Builder().body(
            """{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":$details}}""",
        ).build())
        val result = provider.complete(ChatRequest(ModelTarget(providerId, "model"), listOf(Message.user("hi"))))
        assertEquals(null, result.usage.cacheReadInputTokens)
        assertEquals(12, result.usage.totalTokens)
    }

    companion object {
        @JvmStatic
        fun reasoningFields() = listOf(
            """"reasoning":"Think"""",
            """"reasoning_content":"Think"""",
            """"reasoning":"Think","reasoning_content":"legacy value"""",
            """"reasoning":null,"reasoning_content":"Think"""",
            """"reasoning":"Think","reasoning_content":null""",
        )
    }
}
