package org.tekfive.polyglot.bedrock

import aws.sdk.kotlin.services.bedrockruntime.model.TokenUsage
import org.tekfive.polyglot.*
import java.time.Duration
import kotlin.test.*

class CachingTest {
    private val target = ModelTarget(BedrockProvider.ID, "model")

    @Test fun `converse and streaming carry the same cache checkpoints`() {
        BedrockProvider(BedrockConfig("us-east-1")).use { provider ->
            val request = ChatRequest(target, listOf(Message.system("context"), Message.user("question")),
                caching = PromptCaching(ttl = Duration.ofHours(1), messageBreakpoints = setOf(0, 1)))
            with(provider) {
                val mapped = request.toSdk()
                assertEquals("1h", mapped.system!!.last().asCachePoint().ttl!!.value)
                assertEquals("default", mapped.messages!!.single().content.last().asCachePoint().type.value)
                val stream = request.toStreamSdk()
                assertEquals(mapped.system, stream.system)
                assertEquals(mapped.messages, stream.messages)
            }
        }
    }

    @Test fun `automatic caching appends one checkpoint and default request appends none`() {
        BedrockProvider(BedrockConfig("us-east-1")).use { provider ->
            with(provider) {
                val request = ChatRequest(target, listOf(Message.user("question")))
                assertEquals(1, request.toSdk().messages!!.single().content.size)
                assertEquals(2, request.copy(caching = PromptCaching()).toSdk().messages!!.single().content.size)
                assertFailsWith<UnsupportedCapabilityException> { request.copy(caching = PromptCaching(key = "unsupported")).toSdk() }
                assertFailsWith<IllegalArgumentException> { request.copy(caching = PromptCaching(ttl = Duration.ofMinutes(30))).toSdk() }
            }
        }
    }

    @Test fun `cache tokens are included in normalized input total once`() {
        val usage = TokenUsage {
            inputTokens = 10
            outputTokens = 2
            totalTokens = 12
            cacheReadInputTokens = 100
            cacheWriteInputTokens = 20
        }.toUsage()
        assertEquals(130, usage.inputTokens)
        assertEquals(132, usage.totalTokens)
        assertEquals(100, usage.cacheReadInputTokens)
        assertEquals(20, usage.cacheWriteInputTokens)
    }
}
