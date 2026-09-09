dependencies {
    api(project(":core"))
    api(libs.okhttp)
    implementation(libs.anthropic.java)
    testImplementation(testFixtures(project(":core")))
}
