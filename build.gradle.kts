plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.maven.publish) apply false
}

// The .api dumps are the public surface. Any change to them must appear as a
// reviewed diff, never as a side effect of an unrelated edit.
apiValidation {
    // The sample app is not published, so it has no public API to guard.
    ignoredProjects += listOf("sample", "docs-lint")
}

// Modules that are published. The sample app and the docs lint are tools, not
// products, and listing publishable modules explicitly means adding a new one
// is a deliberate act rather than a side effect of creating a directory.
val kweryVersion = libs.versions.kwery.get()

val publishedModules = setOf(
    "kwery-core",
    "kwery-test",
    "kwery-persist",
    "kwery-android",
    "kwery-compose",
)

subprojects {
    // io.github.<user> is the namespace Maven Central verifies from GitHub
    // account ownership, so it needs no domain. A domain-based group such as
    // dev.kwery would require proving control of kwery.dev by DNS record.
    //
    // JitPack ignores this and serves everything under com.github.dbjpanda —
    // it is here for Maven Central, and for anyone publishing to their own
    // repository.
    group = "io.github.dbjpanda"
    version = kweryVersion

    if (name in publishedModules) {
        apply(plugin = "com.vanniktech.maven.publish")

        // The plugin handles sources, javadoc, signing and the Central Portal
        // upload. Signing is skipped when no key is present, so a contributor
        // running `build` needs nothing.
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral(automaticRelease = true)
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }

            coordinates(groupId = "io.github.dbjpanda", artifactId = name, version = kweryVersion)

            pom {
                name.set(this@subprojects.name)
                description.set(
                    this@subprojects.description
                        ?: "Async server-state management for Android.",
                )
                url.set("https://github.com/dbjpanda/kwery")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("dbjpanda")
                        name.set("Dibyajyoti")
                        url.set("https://github.com/dbjpanda")
                    }
                }
                scm {
                    url.set("https://github.com/dbjpanda/kwery")
                    connection.set("scm:git:https://github.com/dbjpanda/kwery.git")
                    developerConnection.set("scm:git:ssh://git@github.com/dbjpanda/kwery.git")
                }
            }
        }
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            // Every public declaration must state its visibility and return type.
            // This is a published library; accidental public API is permanent.
            explicitApi()

            jvmToolchain(libs.versions.jdkToolchain.get().toInt())

            compilerOptions {
                jvmTarget.set(
                    org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(
                        libs.versions.jvmTarget.get(),
                    ),
                )
                allWarningsAsErrors.set(true)
            }
        }

        // The toolchain compiles with JDK 17, but emitted bytecode must match
        // the Kotlin jvmTarget or Gradle rejects the inconsistency.
        //
        // Scoped to JVM modules only: AGP rejects `--release` outright, because
        // it prevents the plugin from setting up the bootclasspath for
        // compiling against Android APIs. Android modules use
        // source/targetCompatibility in their own build files instead.
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(libs.versions.jvmTarget.get().toInt())
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStackTraces = true
        }
    }
}
