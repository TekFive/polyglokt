package org.tekfive.polyglot

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PolyglotClientTest {
    private val primary = ModelTarget(ProviderId("primary"), "model-a")
    private val fallback = ModelTarget(ProviderId("fallback"), "model-b")

    @Test
    fun `falls back only for retryable provider failures`() = runTest {
        val failing = stubProvider(primary.provider) {
            throw ProviderException(primary.provider, ProviderErrorKind.RATE_LIMIT, "rate limited")
        }
        val succeeding = stubProvider(fallback.provider) {
            ChatResponse(fallback, listOf(ContentPart.Text("ok")))
        }
        val client = PolyglotClient(listOf(failing, succeeding))

        val response = client.complete(
            ChatRequest(primary, listOf(Message.user("hello")), fallbackTargets = listOf(fallback)),
        )

        assertEquals("ok", response.text)
        assertEquals(fallback, response.target)
    }

    @Test
    fun `does not fall back for invalid requests`() = runTest {
        val failing = stubProvider(primary.provider) {
            throw ProviderException(primary.provider, ProviderErrorKind.INVALID_REQUEST, "bad request")
        }
        val succeeding = stubProvider(fallback.provider) {
            ChatResponse(fallback, listOf(ContentPart.Text("should not run")))
        }
        val client = PolyglotClient(listOf(failing, succeeding))

        assertFailsWith<ProviderException> {
            client.complete(ChatRequest(primary, listOf(Message.user("hello")), listOf(fallback)))
    }
}
    @Test
    fun `conversation executes only declared tools and commits history`() = runTest {
        var calls = 0
        val provider = object : ChatProvider {
            override val id = primary.provider
            override val capabilities = setOf(Capability.CHAT, Capability.TOOLS)
            override suspend fun complete(request: ChatRequest): ChatResponse {
                calls++
                return if (calls == 1) {
                    ChatResponse(
                        primary,
                        listOf(ContentPart.ToolCall("1", "weather", JsonObject(emptyMap()))),
                        finishReason = FinishReason.TOOL_CALLS,
                    )
                } else {
                    ChatResponse(primary, listOf(ContentPart.Text("sunny")))
                }
            }
        }
        val conversation = Conversation(
            client = PolyglotClient(listOf(provider)),
            target = primary,
            tools = listOf(ToolDefinition("weather", "Weather", JsonObject(emptyMap()))),
            toolExecutor = ToolExecutor { ToolExecutionResult(JsonPrimitive("72F")) },
        )

        assertEquals("sunny", conversation.say("How is it?").text)
        assertEquals(4, conversation.messages().size)
    }

    private fun stubProvider(id: ProviderId, action: suspend (ChatRequest) -> ChatResponse) =
        object : ChatProvider {
            override val id = id
            override val capabilities = setOf(Capability.CHAT, Capability.STREAMING)
            override suspend fun complete(request: ChatRequest) = action(request)
            override fun stream(request: ChatRequest) = flowOf<StreamEvent>()
        }
}
