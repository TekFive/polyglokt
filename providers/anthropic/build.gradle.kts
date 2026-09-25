dependencies {
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.anthropic.java)
    testImplementation(testFixtures(project(":core")))
}
