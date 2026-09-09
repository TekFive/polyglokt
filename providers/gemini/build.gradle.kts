dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.google.genai)
    testImplementation(testFixtures(project(":core")))
}
