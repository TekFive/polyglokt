dependencies {
    api(project(":core"))
    implementation(project(":providers:openai-compatible"))

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
