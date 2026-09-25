package org.tekfive.polyglot.anthropic

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.tekfive.polyglot.*
import java.time.Duration
import kotlin.test.*

class CachingTest {
    private val server = MockWebServer()
    private lateinit var provider: AnthropicProvider
    @BeforeTest fun setUp() {
        server.start()
        provider = AnthropicProvider(AnthropicConfig("secret", baseUrl = server.url("/").toString(), maxRetries = 0))
    }
    @AfterTest fun tearDown() { provider.close(); server.close() }
    private val target = ModelTarget(AnthropicProvider.ID, "test")
    private val response = """{"id":"r","type":"message","role":"assistant","model":"test","content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":2,"cache_read_input_tokens":100,"cache_creation_input_tokens":20}}"""

    @Test fun `automatic caching and explicit system and message breakpoints map correctly`() = runTest {
        for (explicit in listOf(false, true)) {
            server.enqueue(MockResponse.Builder().body(response).build())
            val result = provider.complete(ChatRequest(target, listOf(Message.system("context"), Message.user("question")),
                caching = PromptCaching(ttl = Duration.ofHours(1), messageBreakpoints = if (explicit) setOf(0, 1) else emptySet())))
            assertEquals(130, result.usage.inputTokens)
            assertEquals(100, result.usage.cacheReadInputTokens)
            assertEquals(20, result.usage.cacheWriteInputTokens)
            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            if (explicit) {
                assertNull(body["cache_control"])
                assertEquals("1h", body["system"]!!.jsonArray[0].jsonObject["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
                assertNotNull(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["cache_control"])
            } else assertEquals("1h", body["cache_control"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `stream accumulator preserves cache read and write counts`() = runTest {
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body("""
            event: message_start
            data: {"type":"message_start","message":{"id":"r","type":"message","role":"assistant","model":"test","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":0,"cache_read_input_tokens":100,"cache_creation_input_tokens":20}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

            event: message_stop
            data: {"type":"message_stop"}

        """.trimIndent() + "\n\n").build())
        val events = provider.stream(ChatRequest(target, listOf(Message.user("hi")), caching = PromptCaching())).toList()
        val usage = assertIs<StreamEvent.Completed>(events.last()).response.usage
        assertEquals(130, usage.inputTokens)
        assertEquals(100, usage.cacheReadInputTokens)
        assertEquals(20, usage.cacheWriteInputTokens)
    }

    @Test fun `user breakpoint does not add an empty system field`() = runTest {
        server.enqueue(MockResponse.Builder().body(response).build())
        provider.complete(ChatRequest(target, listOf(Message.user("document")),
            caching = PromptCaching(messageBreakpoints = setOf(0))))
        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertNull(body["system"])
        assertNotNull(body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["cache_control"])
    }
}
