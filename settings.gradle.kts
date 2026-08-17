pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "polyglotkt"

include(
    ":core",
    ":providers:openai",
    ":providers:anthropic",
    ":providers:gemini",
    ":providers:bedrock",
    ":providers:grok",
    ":providers:openai-compatible",
)
