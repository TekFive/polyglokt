plugins {
    `java-test-fixtures`
}

// Shared HTTPS fixtures are for adapter tests, not published library consumers.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.okhttp)
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(kotlin("test-junit5"))
}
