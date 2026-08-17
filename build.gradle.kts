plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "org.tekfive.polyglotkt"
    version = "0.1.0"
}

configure(subprojects.filterNot { it.path == ":providers" }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(kotlin("test-junit5"))
        "testImplementation"(rootProject.libs.junit.jupiter)
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
                artifactId = "polyglotkt-${project.name}"
                pom {
                    name.set("PolyglotKt ${project.name}")
                    description.set("Kotlin-first, provider-neutral APIs for large language model services.")
                    url.set("https://github.com/TekFive/polyglotkt")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    scm {
                        url.set("https://github.com/TekFive/polyglotkt")
                        connection.set("scm:git:https://github.com/TekFive/polyglotkt.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri(
                    System.getenv("GITHUB_REPOSITORY")?.let { "https://maven.pkg.github.com/$it" }
                        ?: "https://maven.pkg.github.com/TekFive/polyglotkt",
                )
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as String?
                    password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as String?
                }
            }
        }
    }
}
