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
    implementation("org.tekfive.polyglotkt:polyglotkt-core:1.0.0")
    implementation("org.tekfive.polyglotkt:polyglotkt-openai:1.0.0")
    // Add only what you use:
    // implementation("org.tekfive.polyglotkt:polyglotkt-anthropic:1.0.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-gemini:1.0.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-bedrock:1.0.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-grok:1.0.0")
    // implementation("org.tekfive.polyglotkt:polyglotkt-openai-compatible:1.0.0")
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
    val polyglotKtVersion = "v1.0.0" // release tag or commit hash
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

### Fallbacks

Fallback is explicit and conservative. By default, PolyglotKt tries the next target only for rate limits, timeouts, network failures, and provider outages. It never retries an invalid prompt against another provider, and a stream cannot switch providers after emitting content.

```kotlin
val request = ChatRequest(
    target = ModelTarget(OpenAiProvider.ID, "gpt-5.1"),
    fallbackTargets = listOf(ModelTarget(AnthropicProvider.ID, "claude-sonnet-4-5")),
    messages = listOf(Message.user("Hello")),
)
```

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
