# PolygloKt

PolygloKt is a Kotlin-first, provider-neutral API for chat, streaming, tools, structured output, embeddings, and controlled provider fallback.

The project is split into small Gradle modules. Add `polyglotkt-core` plus only the provider adapters you use; an application using Gemini does not download the OpenAI, Anthropic, or AWS SDKs.

## Modules

| Artifact | Implementation | Chat | Stream | Tools | JSON schema | Embeddings |
| --- | --- | :---: | :---: | :---: | :---: | :---: |
| `polyglotkt-core` | Provider-neutral Kotlin API | — | — | — | — | — |
| `polyglotkt-openai` | Official OpenAI Java SDK | ✓ | ✓ | ✓ | ✓ | ✓ |
| `polyglotkt-anthropic` | Official Anthropic Java SDK | ✓ | ✓ | ✓ | ✓ | — |
| `polyglotkt-gemini` | Official Google Gen AI Java SDK | ✓ | ✓ | ✓ | ✓ | ✓ |
| `polyglotkt-bedrock` | Official AWS SDK for Kotlin | ✓ | ✓ | ✓ | — | — |
| `polyglotkt-grok` | xAI preset over the OpenAI-compatible API | ✓ | ✓ | ✓ | ✓ | — |
| `polyglotkt-openai-compatible` | OkHttp Chat Completions adapter | ✓ | ✓ | ✓ | ✓ | ✓ |

Bedrock deliberately advertises only the portable Converse features currently implemented. Capabilities are checked before a request is sent, so unsupported behavior fails locally and predictably.

## Install

Packages are published to GitHub Packages. Configure the repository and choose the adapters you need:

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/TekFive/polyglotkt")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull
                ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull
                ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("org.tekfive.polyglotkt:polyglotkt-core:1.2.0")
    implementation("org.tekfive.polyglotkt:polyglotkt-openai:1.2.0")
    // Add only what you use:
    // implementation("org.tekfive.polyglotkt:polyglotkt-anthropic:1.2.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-gemini:1.2.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-bedrock:1.2.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-grok:1.2.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-openai-compatible:1.2.0")
}
```

### JitPack

JitPack publishes each Gradle subproject separately. Use the individual artifacts so your application downloads only the provider integrations it needs:

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    val polyglotKtVersion = "v1.2.0" // release tag or commit hash
    implementation("com.github.TekFive.polyglotkt:polyglotkt-core:$polyglotKtVersion")
    implementation("com.github.TekFive.polyglotkt:polyglotkt-grok:$polyglotKtVersion")
}
```

The aggregate coordinate `com.github.TekFive:polyglotkt:<version>` includes every provider module and its SDK dependencies. Prefer individual module coordinates unless that is intentional.

## Quick start

```kotlin
import org.tekfive.polyglot.*
import org.tekfive.polyglot.openai.*

val openAi = OpenAiProvider(OpenAiConfig(apiKey = System.getenv("OPENAI_API_KEY")))
val client = PolyglotClient(listOf(openAi))

val response = client.complete(
    ChatRequest(
        target = ModelTarget(OpenAiProvider.ID, "gpt-5.1"),
        messages = listOf(Message.system("Be concise."), Message.user("Why is the sky blue?")),
        options = GenerationOptions(maxOutputTokens = 300),
    ),
)

println(response.text)
openAi.close()
```

Provider adapters are `AutoCloseable`; close them when your application shuts down.

### Custom TLS and endpoints

`AnthropicConfig` and `GeminiConfig` accept an optional `httpClient: OkHttpClient`
and `baseUrl`. Supply a configured client to use a private CA, certificate pins,
connection/read timeouts, or a private gateway. Without a custom client, each SDK
keeps its defaults. Gemini uses the same client for chat, streaming, and embeddings.

Configure the client's `sslSocketFactory` with your CA trust manager and add
`certificatePinner` for public-key restrictions. Keep hostname verification enabled.
Disable `followRedirects` and `followSslRedirects` when trust and credentials must
remain scoped to the configured endpoint. Use HTTPS for that endpoint.

```kotlin
val anthropic = AnthropicProvider(
    AnthropicConfig(apiKey = apiKey, baseUrl = gatewayUrl, httpClient = tlsClient),
)
// GeminiConfig(apiKey = apiKey, baseUrl = gatewayUrl, httpClient = tlsClient)
```

Use a separate HTTP client for each provider. Closing the provider closes its supplied
client's connection pool, dispatcher, and cache. The caller defines CA trust and pins;
PolyglotKt preserves that policy and does not change the JVM trust store.

### Streaming

```kotlin
client.stream(request).collect { event ->
    when (event) {
        is StreamEvent.TextDelta -> print(event.text)
        is StreamEvent.Completed -> println("\n${event.response.usage}")
        else -> Unit
    }
}
```

### Tools and conversations

`Conversation` serializes access, commits history only after a successful turn, and can execute tool calls until the model produces a final answer:

```kotlin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val weather = ToolDefinition(
    name = "weather",
    description = "Get current weather for a city",
    inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("city", buildJsonObject { put("type", "string") })
        })
    },
)

val conversation = Conversation(
    client = client,
    target = ModelTarget(OpenAiProvider.ID, "gpt-5.1"),
    tools = listOf(weather),
    toolExecutor = ToolExecutor { call ->
        ToolExecutionResult(buildJsonObject { put("temperature", 72) })
    },
)

println(conversation.say("What is the weather in Chicago?").text)
```

### JSON output

`ResponseFormat.JsonSchema` asks for an answer that fits a schema and is checked by `Capability.STRUCTURED_OUTPUT`. `ResponseFormat.Json` asks only for well-formed JSON, through the provider's JSON mode, and is checked by `Capability.JSON_OUTPUT`. Use it when a schema is too large or too loose for a provider's structured output, and describe the shape in the prompt:

```kotlin
val response = client.complete(
    ChatRequest(
        target = ModelTarget(GeminiProvider.ID, "gemini-3.5-flash-lite"),
        messages = listOf(Message.system("Answer with one JSON object: {\"title\": string}."), Message.user("Name this policy.")),
        responseFormat = ResponseFormat.Json,
    ),
)
val answer = response.structuredOutput // parsed JSON, or null when the text is not JSON
```

Gemini sends `responseMimeType: application/json` without a schema; OpenAI and OpenAI-compatible services, Grok included, send `response_format: {"type": "json_object"}`, and OpenAI requires the word "JSON" somewhere in the messages. Anthropic and Bedrock have no JSON mode without a schema, so they refuse the request before I/O. `completeJson` accepts either JSON format.

### Fallbacks

Fallback is explicit and conservative. By default, PolyglotKt tries the next target only for rate limits, timeouts, network failures, and provider outages. It never retries an invalid prompt against another provider, and a stream cannot switch providers after emitting content.

```kotlin
val request = ChatRequest(
    target = ModelTarget(OpenAiProvider.ID, "gpt-5.1"),
    fallbackTargets = listOf(ModelTarget(AnthropicProvider.ID, "claude-sonnet-4-5")),
    messages = listOf(Message.user("Hello")),
)
```

### Prompt caching and reusable context

Use the same `PromptCaching` options on a `ChatRequest` or a `Conversation`:

```kotlin
val conversation = Conversation(
    client = client,
    target = target,
    systemMessage = referenceDocument,
    caching = PromptCaching(),
)
val first = conversation.say("Summarize the document.")
val second = conversation.say("Explain its second section.")
println(second.usage.cacheReadInputTokens)
```

`PromptCaching()` selects the adapter's automatic caching behavior. Conversation
still sends its full history; providers can reuse processing of an unchanged prefix.
Keep shared instructions, documents, and tool definitions stable and put changing
questions afterward. Omitting `caching` preserves provider defaults; it does not
disable a provider's implicit caching. Caching is independent of OAuth credentials.

| Adapter | Automatic prefix caching | Routing/accounting key | Explicit message breakpoints | Typed TTL | Stored content |
| --- | --- | --- | --- | --- | --- |
| OpenAI | Provider default | `key` | Supported models | 30 minutes on supported models | — |
| Anthropic | Top-level `cache_control` | — | Yes | 5 minutes / 1 hour | — |
| Gemini | Provider default | — | — | On stored-content creation/update | Create, inspect, extend, delete, reuse |
| Bedrock Converse | Adds a checkpoint after the last message | — | Yes | 5 minutes / 1 hour on supported models | — |
| Grok | Provider default | `key` → `x-grok-conv-id` | — | — | — |
| OpenAI-compatible | Opt-in | Opt-in | Opt-in | Opt-in, OpenAI format | — |

Capabilities describe adapter controls, not a guarantee that every model supports
them or that a request will hit the cache. Model eligibility, minimum prefix size,
expiry, pricing, and retention are enforced by the service. See the provider docs:
[OpenAI](https://developers.openai.com/api/docs/guides/prompt-caching),
[Anthropic](https://platform.claude.com/docs/en/build-with-claude/prompt-caching),
[Gemini](https://ai.google.dev/gemini-api/docs/generate-content/caching),
[Bedrock](https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-caching.html),
[Grok](https://docs.x.ai/developers/advanced-api-usage/prompt-caching/maximizing-cache-hits).

For precise boundaries, set `PromptCaching(messageBreakpoints = setOf(0))` to mark
the end of the first message, including a system message. Up to four message
boundaries can be marked; each uses the same optional `ttl: java.time.Duration`.
With `Conversation`, indices include its optional system message followed by history.
OpenAI breakpoints require a supported content block and cannot mark a tool-call
message. Anthropic and Bedrock render system messages before conversation messages;
keep system instructions at the beginning. Markers cache the rendered prefix through
that boundary, including preceding tool definitions where the provider supports it.

`PromptCaching(key = "session-123")` is available for OpenAI and Grok. On Grok the
per-request key overrides `GrokConfig.conversationId`. Keys do not represent stored
conversation history. For OpenAI models using legacy retention controls, continue
using `providerOptions` (for example `prompt_cache_retention`) without typed caching.
Typed options and raw cache fields cannot be combined, avoiding conflicting settings.

Generic compatible endpoints advertise no caching controls by default. Add
`Capability.PROMPT_CACHING` to their configured `capabilities`, and add `CACHE_KEY`,
`CACHE_TTL`, or `CACHE_BREAKPOINTS` only when the endpoint supports those OpenAI wire
fields. Unsupported typed controls fail before I/O, including on direct adapter calls.

For Gemini, store large context once and send its handle with subsequent questions:

```kotlin
val cache = client.createCachedContent(
    CreateCachedContentRequest(
        target = geminiTarget,
        messages = listOf(Message.user(referenceDocument)),
        ttl = java.time.Duration.ofHours(1),
    ),
)
try {
    val conversation = Conversation(
        client = client,
        target = geminiTarget,
        caching = PromptCaching(cachedContent = cache),
    )
    conversation.say("Summarize the document.")
    conversation.say("Explain its second section.")
    val current = client.getCachedContent(cache)
    val extended = client.updateCachedContent(current, java.time.Duration.ofHours(2))
    println(extended.expiresAt)
} finally {
    client.deleteCachedContent(cache)
}
```

Stored content can include system instructions and tools at creation. Do not resend
those fields on requests using the handle. Handles retain declared tool names so
`Conversation` can validate calls when a `toolExecutor` is supplied. Persist the full
handle if resuming later, and reuse the same provider configuration/account and exact
model. Stored-content requests reject fallback targets: a different provider or model
would not have the cached context. Expired/deleted caches surface the provider error;
the caller decides whether to recreate them. Stored caches are explicitly managed and
are not deleted by closing the provider or clearing a conversation.

`Usage.cacheReadInputTokens` and `cacheWriteInputTokens` report counters when supplied
by the service, for both completed and streaming responses. `null` means unreported;
zero means reported as zero. `inputTokens` includes cache reads and writes, so adding
these counters to it would double-count. Anthropic and Bedrock totals are normalized
from their separate uncached/read/write fields. OpenAI, Grok, compatible APIs, and
Gemini use the provider's total input field; unreported cache-write counts remain null.
This corrects the previous Anthropic/Bedrock input totals for cached requests.

### OpenAI-compatible services

Grok has a dedicated preset with xAI's endpoint, capabilities, and optional conversation-affinity header:

```kotlin
val grok = GrokProvider(
    GrokConfig(
        apiKey = System.getenv("XAI_API_KEY"),
        conversationId = "my-conversation", // optional; improves prompt-cache affinity
    ),
)
```

Use the generic adapter for other services where no suitable official JVM SDK exists:

```kotlin
val deepSeek = OpenAiCompatibleProvider(
    id = ProviderId("deepseek"),
    apiKey = System.getenv("DEEPSEEK_API_KEY"),
    baseUrl = "https://api.deepseek.com/v1",
)
```

The same adapter can target DeepSeek, Groq, OpenRouter, Together, Fireworks, Perplexity, or a local vLLM server. Override `capabilities` when an endpoint implements only a subset of the OpenAI format.

For models that accept `xhigh`, use `GenerationOptions(reasoningEffort = ReasoningEffort.XHIGH)`. The OpenAI-compatible adapter sends this as `reasoning_effort: "xhigh"`. Supported effort levels depend on the target model; configure the adapter with `capabilities = OpenAiCompatibleProvider.DEFAULT_CAPABILITIES + Capability.REASONING` when the endpoint supports reasoning options.

### OAuth bearer credentials

`OpenAiCompatibleProvider` and `GrokConfig` accept an `AccessTokenProvider` in place
of `apiKey`. The provider is called before each chat, embedding, or streaming request
(once per stream collection). Return a valid access token without the `Bearer ` prefix.
Existing API-key constructors remain available.

If your OAuth client already caches and refreshes tokens, connect it directly:

```kotlin
val gateway = OpenAiCompatibleProvider(
    id = ProviderId("gateway"),
    baseUrl = "https://gateway.example.com/v1",
    tokenProvider = AccessTokenProvider {
        oauthClient.validAccessToken() // Your application's suspending OAuth integration.
    },
)
```

Otherwise, `RefreshingAccessTokenProvider` can cache the result of your token exchange:

```kotlin
val credentials = RefreshingAccessTokenProvider {
    val result = oauthClient.acquireOrRefreshToken() // Application-provided integration.
    AccessToken(value = result.accessToken, expiresAt = result.expiresAt)
}

val gateway = OpenAiCompatibleProvider(
    id = ProviderId("gateway"),
    baseUrl = "https://gateway.example.com/v1",
    tokenProvider = credentials,
)
// GrokConfig(tokenProvider = credentials, baseUrl = yourCompatibleGatewayUrl)
```

The cache refreshes 60 seconds before expiry by default (`refreshSkew` is configurable)
and coordinates concurrent requests so they share a successful refresh. Use a separate
instance for each identity, audience, and set of scopes. The callback owns the token
exchange, refresh-token rotation, and secure persistence. Failed refreshes propagate;
expired tokens are never returned. A later request can try acquiring credentials again.

This is support for consuming OAuth bearer tokens, not a browser-login or token-endpoint
client. Your application must configure a supported OAuth flow and obtain tokens for
the destination API. It does not enable ChatGPT or Claude subscription sign-in, and
does not imply that xAI's public endpoint accepts OAuth tokens. The other provider
adapters retain their existing authentication configuration.

Credential acquisition failures become `ProviderErrorKind.AUTHENTICATION`; coroutine
cancellation propagates unchanged. HTTP 401 responses are not automatically refreshed
and replayed, and authentication failures do not trigger the default provider fallback.
Custom token providers must handle concurrent calls. Tokens and cache objects redact
their string representations; applications must also avoid logging token values or
sensitive exceptions from their OAuth client.

For both API-key and token-provider constructors, the compatible adapter rejects an
`Authorization` entry in `extraHeaders` and disables HTTP redirects, including those
enabled on a supplied OkHttp client. Configure the final HTTPS endpoint directly.
Supplied TLS settings, timeouts, and connection pools are preserved; callers remain
responsible for any custom interceptors or authenticators they install.

## API design

- Models are strings scoped by a `ProviderId`, so new model releases do not require a library release.
- Requests use immutable content parts instead of provider-specific message classes.
- Optional features are represented by provider capabilities and checked before I/O.
- Provider exceptions are normalized into stable error categories while retaining status and request IDs where available.
- `providerOptions` is an escape hatch for SDKs that accept arbitrary request fields; common behavior belongs in typed `GenerationOptions`.
- Chat, embeddings, and batch contracts are separate interfaces. A provider does not pretend to support an operation it cannot perform.

This intentionally does not attempt to make every vendor feature look identical. Portable behavior is typed in core; vendor-specific behavior stays at the adapter boundary.

## Build and publish

The project requires JDK 21 and uses the checked-in Gradle wrapper:

```shell
./gradlew check
./gradlew publishToMavenLocal
```

The `publish` GitHub Action tests and publishes every module to this repository's GitHub Packages registry. The `release` action validates the Gradle version, runs the full check, and creates the matching GitHub release.

## License

PolyglotKt is available under the MIT License.
