package org.tekfive.polyglot.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.tekfive.polyglot.ChatProvider
import org.tekfive.polyglot.ChatRequest
import org.tekfive.polyglot.Message
import org.tekfive.polyglot.ModelTarget
import org.tekfive.polyglot.ProviderException
import org.tekfive.polyglot.ProviderId
import org.tekfive.polyglot.StreamEvent
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The same transport contract runs through each vendor SDK without remote API calls. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class TlsProviderTest {
    protected enum class ResponseMode { COMPLETE, STREAM }

    protected val material = TlsTestMaterial()
    protected abstract val providerId: ProviderId
    protected abstract val authHeader: String
    protected abstract fun provider(url: String, client: OkHttpClient): ChatProvider
    protected abstract fun response(mode: ResponseMode): String

    @AfterAll
    fun closeCertificates() {
        material.close()
    }

    @Test
    fun `custom CA and pin support chat and streaming and client cleanup`() = runBlocking {
        withServer { url ->
            val client = client(pin = CertificatePinner.pin(material.certificate("server")))
            val provider = provider(url, client)
            try {
                assertEquals("hello", provider.complete(request()).text)
                val events = provider.stream(request()).toList()
                assertEquals("hello", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.text })
                assertEquals("hello", events.filterIsInstance<StreamEvent.Completed>().single().response.text)
            } finally {
                (provider as AutoCloseable).close()
            }
            assertTrue(client.dispatcher.executorService.isShutdown)
            assertEquals(0, client.connectionPool.connectionCount())
        }
    }

    @Test
    fun `CA trust allows server key rotation`() = runBlocking {
        withServer(name = "rotated") { url ->
            val provider = provider(url, client())
            try {
                assertEquals("hello", provider.complete(request()).text)
            } finally {
                (provider as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `untrusted expired and wrong hostname certificates fail before sending data`() = runBlocking {
        for (name in listOf("other", "wrong-host", "expired")) {
            val received = AtomicInteger()
            withServer(name, { received.incrementAndGet(); respond(it) }) { url ->
                val provider = provider(url, client())
                try {
                    val failure = assertFailsWith<ProviderException> { provider.complete(request()) }
                    assertTlsFailure(failure)
                } finally {
                    (provider as AutoCloseable).close()
                }
            }
            assertEquals(0, received.get(), name)
        }
    }

    @Test
    fun `a valid CA cannot bypass a mismatched pin`() = runBlocking {
        withServer { url ->
            val provider = provider(url, client(pin = CertificatePinner.pin(material.certificate("rotated"))))
            try {
                assertTlsFailure(assertFailsWith<ProviderException> { provider.complete(request()) })
                assertTlsFailure(assertFailsWith<ProviderException> { provider.stream(request()).toList() })
            } finally {
                (provider as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `a pin cannot grant CA trust`() = runBlocking {
        withServer { url ->
            val provider = provider(url, client(ca = null, pin = CertificatePinner.pin(material.certificate("server"))))
            try {
                assertTlsFailure(assertFailsWith<ProviderException> { provider.complete(request()) })
            } finally {
                (provider as AutoCloseable).close()
            }
        }
    }

    @Test
    fun `disabled redirects cannot forward credentials or prompts`() = runBlocking {
        val received = AtomicInteger()
        withServer(handler = { received.incrementAndGet(); respond(it) }) { target ->
            withServer(handler = {
                it.responseHeaders.add("Location", target)
                it.sendResponseHeaders(HttpURLConnection.HTTP_MOVED_TEMP, -1)
                it.close()
            }) { url ->
                val provider = provider(url, client())
                try {
                    assertFailsWith<ProviderException> { provider.complete(request()) }
                } finally {
                    (provider as AutoCloseable).close()
                }
            }
        }
        assertEquals(0, received.get())
    }

    @Test
    fun `the supplied read timeout remains effective`() = runBlocking {
        val release = CountDownLatch(1)
        try {
            withServer(handler = {
                it.sendResponseHeaders(HttpURLConnection.HTTP_OK, 1)
                release.await(5, TimeUnit.SECONDS)
                it.close()
            }) { url ->
                val transport = client().newBuilder().readTimeout(Duration.ofMillis(100)).build()
                val provider = provider(url, transport)
                try {
                    val failure = assertFailsWith<ProviderException> { provider.complete(request()) }
                    assertCause(failure, SocketTimeoutException::class.java)
                } finally {
                    release.countDown()
                    (provider as AutoCloseable).close()
                }
            }
        } finally {
            release.countDown()
        }
    }

    protected fun request(): ChatRequest {
        return ChatRequest(ModelTarget(providerId, "test-model"), listOf(Message.user("hi")))
    }

    protected fun client(ca: String? = material.ca, pin: String? = null): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofSeconds(2))
            .followRedirects(false).followSslRedirects(false)
        if (ca != null) {
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            store.load(null, null)
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ca.byteInputStream())
            store.setCertificateEntry("ca", certificate)
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(store)
            val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trust), null)
            builder.sslSocketFactory(context.socketFactory, trust)
        }
        if (pin != null) {
            builder.certificatePinner(CertificatePinner.Builder().add("127.0.0.1", pin).build())
        }
        return builder.build()
    }

    protected suspend fun withServer(
        name: String = "server",
        handler: (HttpExchange) -> Unit = ::respond,
        action: suspend (String) -> Unit,
    ) {
        // Use one loopback address so IPv6 fallback cannot mask a TLS failure.
        val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(material.context(name))
        server.createContext("/", handler)
        server.start()
        try {
            action("https://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun respond(exchange: HttpExchange) {
        assertEquals("secret", exchange.requestHeaders.getFirst(authHeader))
        val body = exchange.requestBody.bufferedReader().readText()
        val stream = exchange.requestURI.query?.contains("alt=sse") == true || body.contains("\"stream\":true")
        val mode = if (stream) ResponseMode.STREAM else ResponseMode.COMPLETE
        val response = response(mode).toByteArray()
        exchange.responseHeaders.add("Content-Type", if (stream) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
        exchange.close()
    }

    private fun assertTlsFailure(error: Throwable) {
        assertCause(error, SSLException::class.java)
    }

    private fun assertCause(error: Throwable, type: Class<out Throwable>) {
        var cause: Throwable? = error
        while (cause != null) {
            if (type.isInstance(cause)) {
                return
            }
            cause = cause.cause
        }
        throw AssertionError("Expected ${type.simpleName}", error)
    }
}
