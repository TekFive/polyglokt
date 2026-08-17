package org.tekfive.polyglot.openaicompatible

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.EmbeddingRequest
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
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
}
