plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.binary.compatibility.validator)
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
    group = "dev.kwery"
    version = kweryVersion

    if (name in publishedModules) {
        apply(plugin = "maven-publish")

        // An Android library has no publishable component until a variant is
        // nominated. Publishing `release` only: a debug artifact on Maven
        // Central is a support burden with no audience.
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.gradle.LibraryExtension> {
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                        withJavadocJar()
                    }
                }
            }
        }

        plugins.withId("org.jetbrains.kotlin.jvm") {
            extensions.configure<org.gradle.api.plugins.JavaPluginExtension> {
                // Sources and javadoc jars are not decoration: without sources
                // a consumer stepping into Kwery in a debugger sees bytecode,
                // and the KDoc explaining staleTime versus gcTime — the two
                // things every user misreads — never reaches them.
                withSourcesJar()
                withJavadocJar()
            }
        }

        // The publishable component is named differently per plugin, and it
        // does not exist until the plugin has finished configuring — hence
        // afterEvaluate, and hence registering per plugin rather than probing
        // for whichever component happens to be there.
        fun MavenPublication.describe(project: Project) = pom {
            name.set(project.name)
            description.set(
                project.description ?: "Async server-state management for Android.",
            )
            url.set("https://github.com/kwery/kwery")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            scm { url.set("https://github.com/kwery/kwery") }
        }

        plugins.withId("com.android.library") {
            afterEvaluate {
                extensions.configure<PublishingExtension> {
                    publications.create<MavenPublication>("maven") {
                        from(components["release"])
                        describe(this@subprojects)
                    }
                }
            }
        }

        plugins.withId("org.jetbrains.kotlin.jvm") {
            afterEvaluate {
                extensions.configure<PublishingExtension> {
                    publications.create<MavenPublication>("maven") {
                        from(components["java"])
                        describe(this@subprojects)
                    }
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
