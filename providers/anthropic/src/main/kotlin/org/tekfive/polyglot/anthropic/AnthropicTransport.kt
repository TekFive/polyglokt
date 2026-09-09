package org.tekfive.polyglot.anthropic

import com.anthropic.backends.AnthropicBackend
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.errors.AnthropicIoException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture

/** Adapts SDK requests without replacing the supplied client's TLS or timeout configuration. */
internal class AnthropicTransport(
    private val client: OkHttpClient,
    private val backend: AnthropicBackend,
) : HttpClient {
    override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
        try {
            return TransportResponse(call(request).execute())
        } catch (error: IOException) {
            throw AnthropicIoException("Request failed", error)
        } finally {
            request.body?.close()
        }
    }

    override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> {
        val call = call(request)
        val future = CompletableFuture<HttpResponse>()
        future.whenComplete { _, _ ->
            if (future.isCancelled) {
                call.cancel()
            }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                request.body?.close()
                future.completeExceptionally(AnthropicIoException("Request failed", e))
            }

            override fun onResponse(call: Call, response: Response) {
                request.body?.close()
                if (!future.complete(TransportResponse(response))) {
                    response.close()
                }
            }
        })
        return future
    }

    private fun call(request: HttpRequest): Call {
        val prepared = backend.authorizeRequest(backend.prepareRequest(request))
        val url = (prepared.baseUrl ?: backend.baseUrl()).toHttpUrl().newBuilder()
        for (segment in prepared.pathSegments) {
            url.addPathSegment(segment)
        }
        for (key in prepared.queryParams.keys()) {
            for (value in prepared.queryParams.values(key)) {
                url.addQueryParameter(key, value)
            }
        }

        val body = prepared.body?.let { source ->
            object : RequestBody() {
                override fun contentType() = source.contentType()?.toMediaTypeOrNull()
                override fun contentLength() = source.contentLength()
                override fun isOneShot() = !source.repeatable()
                override fun writeTo(sink: BufferedSink) {
                    source.writeTo(sink.outputStream())
                }
            }
        }
        val builder = Request.Builder().url(url.build()).method(prepared.method.name, body)
        for (name in prepared.headers.names()) {
            for (value in prepared.headers.values(name)) {
                builder.addHeader(name, value)
            }
        }
        return client.newCall(builder.build())
    }

    override fun close() {
        backend.close()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    private class TransportResponse(private val response: Response) : HttpResponse {
        override fun statusCode(): Int = response.code
        override fun headers(): Headers = Headers.builder().putAll(response.headers.toMultimap()).build()
        override fun body(): InputStream = response.body.byteStream()
        override fun close() {
            response.close()
        }
    }
}
