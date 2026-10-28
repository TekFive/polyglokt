package org.tekfive.polyglot.gemini

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.ResponseFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonOutputTest {
    private val server = MockWebServer()
    private lateinit var provider: GeminiProvider
    private val target = ModelTarget(GeminiProvider.ID, "test-model")

    @BeforeTest
    fun setUp() {
        server.start()
        provider = GeminiProvider(GeminiConfig(apiKey = "secret", baseUrl = server.url("/").toString()))
    }

    @AfterTest
    fun tearDown() {
        provider.close()
        server.close()
    }

    @Test
    fun `json output asks for JSON without a schema and parses the answer`() = runTest {
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "application/json").body(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"{\"title\":\"Policy\"}"}]},"finishReason":"STOP"}]}""",
            ).build(),
        )

        val response = provider.complete(
            ChatRequest(target, listOf(Message.user("Name this policy.")), responseFormat = ResponseFormat.Json),
        )

        val config = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]?.jsonPrimitive?.content)
        assertNull(config["responseJsonSchema"])
        assertNull(config["responseSchema"])
        assertEquals("Policy", response.structuredOutput?.jsonObject?.get("title")?.jsonPrimitive?.content)
    }
}
