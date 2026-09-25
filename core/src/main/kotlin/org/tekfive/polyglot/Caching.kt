package org.tekfive.polyglot

import java.time.Duration
import java.time.Instant

/**
 * Requests provider-side prompt caching. Empty options use the adapter's automatic caching mode.
 * Breakpoints mark the end of messages by zero-based index in [ChatRequest.messages], including
 * system messages. TTLs and keys are provider-specific controls, not guarantees of cache hits.
 * Null caching leaves provider defaults unchanged; it does not disable implicit caching.
 */
data class PromptCaching(
    val key: String? = null,
    val ttl: Duration? = null,
    val messageBreakpoints: Set<Int> = emptySet(),
    val cachedContent: CachedContentHandle? = null,
) {
    init {
        key?.let { require(it.isNotBlank()) { "Cache key must not be blank" } }
        ttl?.let { require(!it.isNegative && !it.isZero) { "Cache TTL must be positive" } }
        require(messageBreakpoints.all { it >= 0 }) { "Cache breakpoint indices must not be negative" }
        require(messageBreakpoints.size <= 4) { "At most four cache breakpoints are supported" }
        require(cachedContent == null || (key == null && ttl == null && messageBreakpoints.isEmpty())) {
            "Stored content cannot be combined with prompt cache controls"
        }
    }
}

/** Scoped to the creating provider configuration/account and the exact model target. */
data class CachedContentHandle(
    val target: ModelTarget,
    val id: String,
    val expiresAt: Instant? = null,
    val inputTokens: Long? = null,
    /** Tool declarations stored with the cache; retained so Conversation can validate tool calls. */
    val toolNames: Set<String> = emptySet(),
) {
    init { require(id.isNotBlank()) { "Cache ID must not be blank" } }
}

data class CreateCachedContentRequest(
    val target: ModelTarget,
    val messages: List<Message>,
    val ttl: Duration? = null,
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice? = null,
    val displayName: String? = null,
) {
    init {
        require(messages.isNotEmpty()) { "Cached content must not be empty" }
        ttl?.let { require(!it.isNegative && !it.isZero) { "Cache TTL must be positive" } }
    }
}

/** For providers offering separately stored content, currently Gemini. Operations never fall back. */
interface CachedContentProvider : ModelProvider {
    suspend fun createCachedContent(request: CreateCachedContentRequest): CachedContentHandle
    suspend fun getCachedContent(handle: CachedContentHandle): CachedContentHandle
    suspend fun updateCachedContent(handle: CachedContentHandle, ttl: Duration): CachedContentHandle
    suspend fun deleteCachedContent(handle: CachedContentHandle)
}

/** Shared by the router and adapters, so direct adapter calls receive the same validation. */
fun ChatRequest.validateCaching(provider: ModelProvider) {
    val cache = caching ?: return
    val required = buildSet {
        add(if (cache.cachedContent != null) Capability.CACHED_CONTENT else Capability.PROMPT_CACHING)
        if (cache.key != null) add(Capability.CACHE_KEY)
        if (cache.ttl != null) add(Capability.CACHE_TTL)
        if (cache.messageBreakpoints.isNotEmpty()) add(Capability.CACHE_BREAKPOINTS)
    }
    required.firstOrNull { it !in provider.capabilities }?.let {
        throw UnsupportedCapabilityException(provider.id, it)
    }
    require(cache.messageBreakpoints.all { it in messages.indices && messages[it].content.isNotEmpty() }) {
        "Cache breakpoints must refer to nonempty messages"
    }
    cache.cachedContent?.let {
        require(it.target == target && target.provider == provider.id) { "Cached content belongs to a different target" }
        require(fallbackTargets.isEmpty()) { "Stored content requests cannot use fallback targets" }
        require(messages.none { message -> message.role == MessageRole.SYSTEM } && tools.isEmpty() && toolChoice == null) {
            "System instructions and tools must be configured when stored content is created"
        }
    }
    require(providerOptions.keys.none { it in CACHE_BODY_FIELDS }) {
        "Typed caching cannot be combined with raw provider cache fields"
    }
}

private val CACHE_BODY_FIELDS = setOf(
    "cache_control", "prompt_cache_key", "prompt_cache_options", "prompt_cache_retention",
    "cached_content", "cachedContent", "messages", "system", "tools",
)
