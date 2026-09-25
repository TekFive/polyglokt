package org.tekfive.polyglot.gemini

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.tekfive.polyglot.*
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class CachingTest {
    private val server = MockWebServer()
    private lateinit var provider: GeminiProvider
    private lateinit var client: PolyglotClient
    private val target = ModelTarget(GeminiProvider.ID, "test-model")
    private val cached = """{"name":"cachedContents/test","model":"models/test-model","expireTime":"2027-01-01T00:00:00Z","usageMetadata":{"totalTokenCount":100}}"""
    private val generated = """{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":110,"cachedContentTokenCount":100,"candidatesTokenCount":2}}"""

    @BeforeTest fun setUp() {
        server.start()
        provider = GeminiProvider(GeminiConfig(apiKey = "secret", baseUrl = server.url("/").toString()))
        client = PolyglotClient(listOf(provider))
    }
    @AfterTest fun tearDown() { provider.close(); server.close() }
    private fun enqueue(body: String) { server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(body).build()) }
    private fun body() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test fun `creates reuses inspects extends and deletes cached context`() = runTest {
        enqueue(cached)
        val handle = client.createCachedContent(CreateCachedContentRequest(target,
            listOf(Message.system("stable instructions"), Message.user("long reference document")), ttl = Duration.ofHours(1),
            tools = listOf(ToolDefinition("lookup", "Look up information", buildJsonObject { put("type", "object") }))))
        assertEquals("cachedContents/test", handle.id)
        assertEquals(100, handle.inputTokens)
        assertEquals(setOf("lookup"), handle.toolNames)
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), handle.expiresAt)
        val creation = body()
        assertEquals("3600s", creation["ttl"]!!.jsonPrimitive.content)
        assertTrue(creation.toString().contains("long reference document"))
        assertNotNull(creation["systemInstruction"])
        assertNotNull(creation["tools"])

        val conversation = Conversation(client, target, caching = PromptCaching(cachedContent = handle))
        repeat(2) {
            enqueue(generated)
            val response = conversation.say("question $it")
            assertEquals(110, response.usage.inputTokens)
            assertEquals(100, response.usage.cacheReadInputTokens)
            assertEquals(112, response.usage.totalTokens)
            val generation = body()
            assertEquals(handle.id, generation["cachedContent"]!!.jsonPrimitive.content)
            assertFalse(generation.toString().contains("long reference document"))
            assertNull(generation["systemInstruction"])
        }
        enqueue(cached)
        assertEquals(handle, client.getCachedContent(handle))
        assertEquals("GET", server.takeRequest().method)
        enqueue(cached)
        client.updateCachedContent(handle, Duration.ofMinutes(30))
        val update = server.takeRequest()
        assertEquals("PATCH", update.method)
        assertEquals("1800s", Json.parseToJsonElement(update.body!!.utf8()).jsonObject["ttl"]!!.jsonPrimitive.content)
        enqueue("{}")
        client.deleteCachedContent(handle)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test fun `stream sends cached reference and reports cache hits`() = runTest {
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body("data: $generated\n\n").build())
        val request = ChatRequest(target, listOf(Message.user("hi")), caching = PromptCaching(cachedContent = CachedContentHandle(target, "cachedContents/test")))
        val response = assertIs<StreamEvent.Completed>(client.stream(request).toList().last()).response
        assertEquals(100, response.usage.cacheReadInputTokens)
        assertEquals("cachedContents/test", body()["cachedContent"]!!.jsonPrimitive.content)
    }

    @Test fun `unsupported controls and conflicting cached system fail before IO`() = runTest {
        assertFailsWith<UnsupportedCapabilityException> {
            provider.complete(ChatRequest(target, listOf(Message.user("hi")), caching = PromptCaching(key = "key")))
        }
        assertFailsWith<IllegalArgumentException> {
            provider.complete(ChatRequest(target, listOf(Message.system("conflict"), Message.user("hi")),
                caching = PromptCaching(cachedContent = CachedContentHandle(target, "cachedContents/test"))))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `expired or missing stored context returns provider error without fallback`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"code":404,"message":"Cache expired","status":"NOT_FOUND"}}""").build())
        val error = assertFailsWith<ProviderException> {
            client.getCachedContent(CachedContentHandle(target, "cachedContents/expired"))
        }
        assertEquals(ProviderErrorKind.NOT_FOUND, error.kind)
        assertEquals(1, server.requestCount)
    }
}
