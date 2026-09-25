dependencies {
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    api(project(":core"))
    implementation(libs.openai.java)
}
