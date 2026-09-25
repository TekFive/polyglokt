package org.tekfive.polyglot.openai

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
    private lateinit var provider: OpenAiProvider
    @BeforeTest fun setUp() {
        server.start()
        provider = OpenAiProvider(OpenAiConfig("secret", baseUrl = server.url("/v1").toString(), maxRetries = 0))
    }
    @AfterTest fun tearDown() { provider.close(); server.close() }

    @Test fun `maps cache key ttl breakpoints and usage in complete and stream`() = runTest {
        val request = ChatRequest(
            ModelTarget(OpenAiProvider.ID, "test"), listOf(Message.system("context"), Message.user("question")),
            caching = PromptCaching(key = "session", ttl = Duration.ofMinutes(30), messageBreakpoints = setOf(0)),
        )
        for (streaming in listOf(false, true)) {
            val usage = """"usage":{"prompt_tokens":110,"completion_tokens":2,"total_tokens":112,"prompt_tokens_details":{"cached_tokens":100}}"""
            val response = if (streaming) """data: {"id":"r","object":"chat.completion.chunk","created":1,"model":"test","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}],$usage}

data: [DONE]

""" else """{"id":"r","object":"chat.completion","created":1,"model":"test","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],$usage}"""
            server.enqueue(MockResponse.Builder().body(response).setHeader("Content-Type", if (streaming) "text/event-stream" else "application/json").build())
            val result = if (streaming) assertIs<StreamEvent.Completed>(provider.stream(request).toList().last()).response else provider.complete(request)
            assertEquals(110, result.usage.inputTokens)
            assertEquals(100, result.usage.cacheReadInputTokens)
            assertEquals(112, result.usage.totalTokens)
            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
            assertEquals("session", body["prompt_cache_key"]!!.jsonPrimitive.content)
            assertEquals("30m", body["prompt_cache_options"]!!.jsonObject["ttl"]!!.jsonPrimitive.content)
            val system = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
            assertEquals("explicit", system["prompt_cache_breakpoint"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `unsupported ttl fails before HTTP`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            provider.complete(ChatRequest(ModelTarget(OpenAiProvider.ID, "test"), listOf(Message.user("hi")), caching = PromptCaching(ttl = Duration.ofMinutes(5))))
        }
        assertEquals(0, server.requestCount)
    }
}
