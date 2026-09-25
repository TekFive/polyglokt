package org.tekfive.polyglot.openaicompatible

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.tekfive.polyglot.AccessTokenProvider
import org.tekfive.polyglot.Capability
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.ChatResponse
import org.tekfive.polyglot.EmbeddingRequest
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.PolyglotClient
import org.tekfive.polyglot.ProviderErrorKind
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class AuthenticationTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val id = ProviderId("oauth")
    private val target = ModelTarget(id, "model")
    private val request = ChatRequest(target, listOf(Message.user("hello")))

    @BeforeTest
    fun setUp() = server.start()

    @AfterTest
    fun tearDown() {
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun provider(tokens: AccessTokenProvider) = OpenAiCompatibleProvider(
        id, tokens, server.url("/").toString(), client,
    )

    @Test
    fun `resolves fresh credentials for chat embeddings and each stream collection`() = runTest {
        var calls = 0
        val provider = provider { "token-${++calls}" }
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"content":"hello"}}]}""").build())
        provider.complete(request)
        server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
        provider.embed(EmbeddingRequest(target, listOf("hello")))
        val stream = provider.stream(request)
        assertEquals(2, calls)
        repeat(2) {
            server.enqueue(MockResponse.Builder().body("data: [DONE]\n\n").build())
            stream.toList()
        }
        repeat(4) { index ->
            assertEquals(listOf("Bearer token-${index + 1}"), server.takeRequest().headers.values("Authorization"))
        }
        assertEquals(4, calls)
    }

    @Test
    fun `credential failure is authentication failure and does not invoke fallback`() = runTest {
        val provider = provider { error("sensitive upstream detail") }
        val fallback = object : ChatProvider {
            override val id = ProviderId("fallback")
            override val capabilities = setOf(Capability.CHAT)
            override suspend fun complete(request: ChatRequest): ChatResponse = error("Fallback must not run")
        }
        val error = assertFailsWith<ProviderException> {
            PolyglotClient(listOf(provider, fallback)).complete(
                request.copy(fallbackTargets = listOf(ModelTarget(fallback.id, "model"))),
            )
        }
        assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind)
        assertEquals(id, error.provider)
        assertFalse(error.retryable)
        assertFalse(error.message.orEmpty().contains("sensitive"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cancellation propagates without sending a request`() = runTest {
        val cancellation = CancellationException("cancelled")
        val error = assertFailsWith<CancellationException> {
            provider { throw cancellation }.complete(request)
        }
        assertSame(cancellation, error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `invalid tokens and competing authorization headers are rejected`() = runTest {
        for (token in listOf("", "Bearer secret", "secret\r\nInjected: header")) {
            val error = assertFailsWith<ProviderException> { provider { token }.complete(request) }
            assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind)
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAiCompatibleProvider(
                id, "secret", server.url("/").toString(), client,
                extraHeaders = mapOf("aUtHoRiZaTiOn" to "Bearer other"),
            )
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `unauthorized responses are not replayed for any operation`() = runTest {
        var calls = 0
        val provider = provider { "token-${++calls}" }
        val operations: List<suspend () -> Unit> = listOf(
            { provider.complete(request) },
            { provider.embed(EmbeddingRequest(target, listOf("hello"))) },
            { provider.stream(request).toList() },
        )
        for (operation in operations) {
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"expired"}}""").build())
            val error = assertFailsWith<ProviderException> { operation() }
            assertEquals(ProviderErrorKind.AUTHENTICATION, error.kind)
        }
        assertEquals(3, calls)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `does not follow redirects even on the same host`() = runTest {
        server.enqueue(MockResponse.Builder().code(307).setHeader("Location", server.url("/other")).build())
        // Makes an accidental redirect finish promptly, then fail the assertion below.
        server.enqueue(MockResponse.Builder().code(401).build())
        val error = assertFailsWith<ProviderException> { provider { "secret" }.complete(request) }
        assertEquals(307, error.statusCode)
        assertEquals(1, server.requestCount)
    }
}
