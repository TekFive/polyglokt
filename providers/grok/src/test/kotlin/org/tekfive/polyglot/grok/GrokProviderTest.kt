package org.tekfive.polyglot.grok

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.GenerationOptions
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
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
