package org.tekfive.polyglot

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Supplies a valid bearer token before each request. Implementations must support concurrent calls. */
fun interface AccessTokenProvider {
    /** Acquire or refresh credentials as needed. Return the token without the `Bearer ` prefix. */
    suspend fun accessToken(): String
}

/** An expiring credential. Its string representation never includes the token. */
class AccessToken(val value: String, val expiresAt: Instant) {
    init {
        require(value.isNotBlank() && value.all { it.code in 33..126 }) { "Invalid access token" }
    }

    override fun toString() = "AccessToken(value=<redacted>, expiresAt=$expiresAt)"
}

/**
 * Caches tokens and serializes refreshes. [fetchToken] owns OAuth exchange, refresh-token rotation,
 * and persistence; it must return a currently valid token or throw. Failures never return stale tokens.
 * Use a separate instance for each identity, audience, and set of scopes.
 */
class RefreshingAccessTokenProvider(
    private val clock: Clock = Clock.systemUTC(),
    private val refreshSkew: Duration = Duration.ofSeconds(60),
    private val fetchToken: suspend () -> AccessToken,
) : AccessTokenProvider {
    private val mutex = Mutex()
    private var cached: AccessToken? = null

    init {
        require(!refreshSkew.isNegative) { "refreshSkew must not be negative" }
    }

    override suspend fun accessToken(): String = mutex.withLock {
        cached?.takeIf { clock.instant().plus(refreshSkew).isBefore(it.expiresAt) }?.let {
            return@withLock it.value
        }
        val fresh = fetchToken()
        require(fresh.expiresAt.isAfter(clock.instant())) { "Token provider returned an expired access token" }
        cached = fresh
        fresh.value
    }

    override fun toString() = "RefreshingAccessTokenProvider(credentials=<redacted>)"
}
