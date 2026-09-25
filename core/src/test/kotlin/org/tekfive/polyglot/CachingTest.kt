package org.tekfive.polyglot

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CachingTest {
    private val target = ModelTarget(ProviderId("test"), "model")

    @Test
    fun `conversation preserves cache policy and stable prefix across turns`() = runTest {
        val requests = mutableListOf<ChatRequest>()
        val provider = object : ChatProvider {
            override val id = target.provider
            override val capabilities = setOf(Capability.CHAT, Capability.PROMPT_CACHING, Capability.CACHE_BREAKPOINTS)
            override suspend fun complete(request: ChatRequest): ChatResponse {
                requests += request
                return ChatResponse(request.target, listOf(ContentPart.Text("answer")))
            }
        }
        val caching = PromptCaching(messageBreakpoints = setOf(0))
        val conversation = Conversation(PolyglotClient(listOf(provider)), target, systemMessage = "stable context", caching = caching)
        conversation.say("first")
        conversation.say("second")
        assertEquals(listOf(caching, caching), requests.map { it.caching })
        assertEquals(requests.first().messages, requests.last().messages.take(2))
        assertEquals(4, requests.last().messages.size)
    }

    @Test
    fun `conversation validates and executes tools declared in stored content`() = runTest {
        var calls = 0
        val provider = object : ChatProvider {
            override val id = target.provider
            override val capabilities = setOf(Capability.CHAT, Capability.CACHED_CONTENT, Capability.TOOLS)
            override suspend fun complete(request: ChatRequest): ChatResponse {
                assertEquals(emptyList(), request.tools)
                return ChatResponse(target, if (calls++ == 0) {
                    listOf(ContentPart.ToolCall("call", "lookup", JsonObject(emptyMap())))
                } else listOf(ContentPart.Text("answer")))
            }
        }
        val handle = CachedContentHandle(target, "cache", toolNames = setOf("lookup"))
        val conversation = Conversation(PolyglotClient(listOf(provider)), target,
            caching = PromptCaching(cachedContent = handle),
            toolExecutor = ToolExecutor { ToolExecutionResult(JsonPrimitive("result")) })
        assertEquals("answer", conversation.say("question").text)
        assertEquals(2, calls)
        assertEquals(4, conversation.messages().size)
    }

    @Test
    fun `unsupported caching fails before provider IO`() = runTest {
        val provider = object : ChatProvider {
            override val id = target.provider
            override val capabilities = setOf(Capability.CHAT)
            override suspend fun complete(request: ChatRequest): ChatResponse = error("Must not call provider")
        }
        val error = assertFailsWith<UnsupportedCapabilityException> {
            PolyglotClient(listOf(provider)).complete(ChatRequest(target, listOf(Message.user("hi")), caching = PromptCaching()))
        }
        assertEquals(Capability.PROMPT_CACHING, error.capability)
    }

    @Test
    fun `stored content cannot switch model provider or fallback`() {
        val handle = CachedContentHandle(target, "cachedContents/123")
        val caching = PromptCaching(cachedContent = handle)
        assertFailsWith<IllegalArgumentException> {
            ChatRequest(target.copy(model = "other"), listOf(Message.user("hi")), caching = caching)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatRequest(target.copy(provider = ProviderId("other")), listOf(Message.user("hi")), caching = caching)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatRequest(target, listOf(Message.user("hi")), fallbackTargets = listOf(target), caching = caching)
        }
    }

    @Test
    fun `invalid cache settings and conflicting raw fields fail locally`() {
        val provider = object : ModelProvider {
            override val id = target.provider
            override val capabilities = Capability.entries.toSet()
        }
        assertFailsWith<IllegalArgumentException> { PromptCaching(ttl = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { PromptCaching(messageBreakpoints = setOf(-1)) }
        assertFailsWith<IllegalArgumentException> {
            PromptCaching(cachedContent = CachedContentHandle(target, "cache"), key = "key")
        }
        assertFailsWith<IllegalArgumentException> {
            ChatRequest(target, listOf(Message.user("hi")), caching = PromptCaching(messageBreakpoints = setOf(1))).validateCaching(provider)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatRequest(target, listOf(Message.user("hi")), caching = PromptCaching(),
                providerOptions = JsonObject(mapOf("prompt_cache_key" to JsonPrimitive("raw")))).validateCaching(provider)
        }
    }
}
