package org.tekfive.polyglot

import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface ModelProvider {
    val id: ProviderId
    val capabilities: Set<Capability>
}

interface ChatProvider : ModelProvider {
    suspend fun complete(request: ChatRequest): ChatResponse

    fun stream(request: ChatRequest): Flow<StreamEvent> {
        throw UnsupportedCapabilityException(id, Capability.STREAMING)
    }
}

data class EmbeddingRequest(
    val target: ModelTarget,
    val input: List<String>,
    val fallbackTargets: List<ModelTarget> = emptyList(),
    val dimensions: Int? = null,
) {
    init {
        require(input.isNotEmpty()) { "Embedding input must not be empty" }
        dimensions?.let { require(it > 0) { "dimensions must be positive" } }
    }

    internal fun forTarget(target: ModelTarget) = copy(target = target, fallbackTargets = emptyList())
}

data class EmbeddingResponse(
    val target: ModelTarget,
    val embeddings: List<FloatArray>,
    val usage: Usage = Usage(),
)

interface EmbeddingProvider : ModelProvider {
    suspend fun embed(request: EmbeddingRequest): EmbeddingResponse
}

data class BatchItem(val id: String, val request: ChatRequest)
data class BatchHandle(val provider: ProviderId, val id: String)

enum class BatchState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED,
}

data class BatchStatus(
    val handle: BatchHandle,
    val state: BatchState,
    val counts: Map<String, Long> = emptyMap(),
)

data class BatchResult(
    val id: String,
    val response: ChatResponse? = null,
    val error: ProviderException? = null,
)

interface BatchProvider : ModelProvider {
    suspend fun submit(items: List<BatchItem>): BatchHandle
    suspend fun status(handle: BatchHandle): BatchStatus
    suspend fun results(handle: BatchHandle): List<BatchResult>
    suspend fun cancel(handle: BatchHandle): BatchStatus =
        throw UnsupportedCapabilityException(id, Capability.BATCH, "Batch cancellation is not supported")
}

enum class ProviderErrorKind {
    INVALID_REQUEST,
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    RATE_LIMIT,
    TIMEOUT,
    NETWORK,
    PROVIDER_UNAVAILABLE,
    INVALID_RESPONSE,
    UNKNOWN,
}

open class PolyglotException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ProviderException(
    val provider: ProviderId,
    val kind: ProviderErrorKind,
    message: String,
    val statusCode: Int? = null,
    val requestId: String? = null,
    val retryAt: Instant? = null,
    cause: Throwable? = null,
) : PolyglotException(message, cause) {
    val retryable: Boolean
        get() = kind in setOf(
            ProviderErrorKind.RATE_LIMIT,
            ProviderErrorKind.TIMEOUT,
            ProviderErrorKind.NETWORK,
            ProviderErrorKind.PROVIDER_UNAVAILABLE,
        )
}

class UnsupportedCapabilityException(
    val provider: ProviderId,
    val capability: Capability,
    detail: String = "Provider $provider does not support $capability",
) : PolyglotException(detail)

class ProviderNotRegisteredException(provider: ProviderId) :
    PolyglotException("No provider is registered for '$provider'")

fun interface FallbackPolicy {
    fun shouldFallback(error: ProviderException, nextTarget: ModelTarget): Boolean

    companion object {
        val RetryableOnly = FallbackPolicy { error, _ -> error.retryable }
    }
}
