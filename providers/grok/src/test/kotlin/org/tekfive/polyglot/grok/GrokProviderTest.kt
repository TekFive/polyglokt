package org.tekfive.polyglot.grok

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.tekfive.polyglot.AccessTokenProvider
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.GenerationOptions
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.PromptCaching
import org.tekfive.polyglot.ReasoningEffort
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GrokProviderTest {
    private val server = MockWebServer()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `uses xAI defaults and conversation affinity header`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"id":"req-1","choices":[{"message":{"content":"hello"},"finish_reason":"stop"}]}""",
            ).build(),
        )
        val provider = GrokProvider(
            GrokConfig(
                apiKey = "xai-secret",
                baseUrl = server.url("/").toString(),
                conversationId = "conversation-1",
            ),
        )

        val response = provider.complete(
            ChatRequest(
                ModelTarget(GrokProvider.ID, "grok-test"),
                listOf(Message.user("hi")),
                options = GenerationOptions(reasoningEffort = ReasoningEffort.HIGH),
            ),
        )

        assertEquals("hello", response.text)
        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        assertEquals("conversation-1", request.headers[GrokProvider.CONVERSATION_ID_HEADER])
        assertEquals("Bearer xai-secret", request.headers["Authorization"])
        assertTrue(assertNotNull(request.body).utf8().contains("\"reasoning_effort\":\"high\""))
    }

    @Test
    fun `passes rotating tokens through to the compatible adapter`() = runTest {
        var calls = 0
        val provider = GrokProvider(
            GrokConfig(
                tokenProvider = AccessTokenProvider { "token-${++calls}" },
                baseUrl = server.url("/").toString(),
            ),
        )
        repeat(2) { index ->
            server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"content":"hello"}}]}""").build())
            provider.complete(ChatRequest(ModelTarget(GrokProvider.ID, "model"), listOf(Message.user("hi"))))
            assertEquals("Bearer token-${index + 1}", server.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `per request cache key overrides configured affinity without OpenAI cache body fields`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"content":"ok"}}],"usage":{"prompt_tokens":110,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":100}}}""").build())
        val provider = GrokProvider(GrokConfig("secret", server.url("/").toString(), conversationId = "default"))
        val result = provider.complete(ChatRequest(ModelTarget(GrokProvider.ID, "model"), listOf(Message.user("hi")), caching = PromptCaching(key = "session")))
        val recorded = server.takeRequest()
        assertEquals("session", recorded.headers[GrokProvider.CONVERSATION_ID_HEADER])
        assertFalse(recorded.body!!.utf8().contains("prompt_cache"))
        assertEquals(100, result.usage.cacheReadInputTokens)
    }

    @Test
    fun `advertises only supported portable capabilities and redacts secrets`() {
        assertTrue(Capability.REASONING in GrokProvider.CAPABILITIES)
        assertTrue(Capability.IMAGE_INPUT in GrokProvider.CAPABILITIES)
        assertFalse(Capability.EMBEDDINGS in GrokProvider.CAPABILITIES)
        assertFalse(Capability.AUDIO_INPUT in GrokProvider.CAPABILITIES)

        val rendered = GrokConfig("secret", extraHeaders = mapOf("Authorization-Extra" to "also-secret")).toString()
        assertFalse(rendered.contains("also-secret"))
        assertFalse(rendered.contains("apiKey=secret"))
    }
}
