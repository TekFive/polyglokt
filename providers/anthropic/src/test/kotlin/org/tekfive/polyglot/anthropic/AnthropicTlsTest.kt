package org.tekfive.polyglot.anthropic

import okhttp3.OkHttpClient
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.testing.TlsProviderTest

class AnthropicTlsTest : TlsProviderTest() {
    override val providerId = AnthropicProvider.ID
    override val authHeader = "x-api-key"

    override fun provider(url: String, client: OkHttpClient): ChatProvider {
        return AnthropicProvider(AnthropicConfig("secret", baseUrl = url, maxRetries = 0, httpClient = client))
    }

    override fun response(mode: ResponseMode): String {
        if (mode == ResponseMode.COMPLETE) {
            return """{"id":"msg_test","type":"message","role":"assistant","model":"test-model","content":[{"type":"text","text":"hello"}],"stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
        }
        return """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant","model":"test-model","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hello"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":1}}

            event: message_stop
            data: {"type":"message_stop"}


        """.trimIndent()
    }
}
