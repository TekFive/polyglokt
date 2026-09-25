package org.tekfive.polyglot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AuthenticationTest {
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    @Test
    fun `caches tokens and refreshes once for concurrent requests near expiry`() = runTest {
        var calls = 0
        val provider = RefreshingAccessTokenProvider(clock = clock) {
            calls++
            delay(10)
            AccessToken("token-$calls", now.plusSeconds(300))
        }

        assertEquals(List(20) { "token-1" }, List(20) { async { provider.accessToken() } }.awaitAll())
        now = now.plusSeconds(239)
        assertEquals("token-1", provider.accessToken())
        now = now.plusSeconds(1)
        assertEquals(List(20) { "token-2" }, List(20) { async { provider.accessToken() } }.awaitAll())
        assertEquals(2, calls)
    }

    @Test
    fun `failed refresh does not serve expired credentials and can recover`() = runTest {
        var failure: Exception? = null
        val provider = RefreshingAccessTokenProvider(clock = clock) {
            failure?.let { throw it }
            AccessToken("token", now.plusSeconds(300))
        }
        provider.accessToken()
        now = now.plusSeconds(301)
        failure = IllegalStateException("refresh failed")
        assertFailsWith<IllegalStateException> { provider.accessToken() }
        failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { provider.accessToken() }
        failure = null
        assertEquals("token", provider.accessToken())
    }

    @Test
    fun `rejects expired fetched tokens and invalid configuration`() = runTest {
        val provider = RefreshingAccessTokenProvider(clock = clock) { AccessToken("secret", now) }
        assertFailsWith<IllegalArgumentException> { provider.accessToken() }
        assertFailsWith<IllegalArgumentException> {
            RefreshingAccessTokenProvider(refreshSkew = Duration.ofSeconds(-1)) { AccessToken("secret", now) }
        }
        for (value in listOf("", "Bearer secret", "secret\r\nInjected: header")) {
            assertFailsWith<IllegalArgumentException> { AccessToken(value, now) }
        }
    }

    @Test
    fun `credential representations redact secrets`() {
        assertFalse(AccessToken("secret", now).toString().contains("secret"))
        val loader = object : suspend () -> AccessToken {
            override suspend fun invoke() = AccessToken("secret", now)
            override fun toString() = "secret"
        }
        assertFalse(RefreshingAccessTokenProvider(fetchToken = loader).toString().contains("secret"))
    }
}
