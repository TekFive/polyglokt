package org.tekfive.polyglot.gemini

import com.google.genai.types.HttpRetryOptions
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.EmbeddingRequest
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.testing.TlsProviderTest
import java.net.HttpURLConnection
import kotlin.test.assertContentEquals

class GeminiTlsTest : TlsProviderTest() {
    override val providerId = GeminiProvider.ID
    override val authHeader = "x-goog-api-key"

    override fun provider(url: String, client: OkHttpClient): ChatProvider {
        // Expected TLS failures should not wait through the SDK's retry backoff.
        val transport = client.newBuilder().addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .tag(HttpRetryOptions::class.java, HttpRetryOptions.builder().attempts(1).build()).build()
            chain.proceed(request)
        }.build()
        return GeminiProvider(GeminiConfig(apiKey = "secret", baseUrl = url, httpClient = transport))
    }

    override fun response(mode: ResponseMode): String {
        val response = """{"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1}}"""
        if (mode == ResponseMode.STREAM) {
            return "data: $response\n\n"
        }
        return response
    }

    @Test
    fun `embeddings use the configured TLS client`() = runBlocking {
        withServer(handler = {
            val response = """{"embeddings":[{"values":[0.25,0.75]}]}""".toByteArray()
            it.responseHeaders.add("Content-Type", "application/json")
            it.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.size.toLong())
            it.responseBody.use { body -> body.write(response) }
            it.close()
        }) { url ->
            GeminiProvider(GeminiConfig(apiKey = "secret", baseUrl = url, httpClient = client())).use { provider ->
                val response = provider.embed(EmbeddingRequest(ModelTarget(providerId, "test-model"), listOf("hi")))
                assertContentEquals(floatArrayOf(0.25f, 0.75f), response.embeddings.single())
            }
        }
    }
}
