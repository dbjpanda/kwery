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
        apply(plugin = "maven-publish")
        apply(plugin = "signing")

        // Maven Central requires every artifact to be signed. The key is read
        // from properties rather than a keyring file so the same configuration
        // works locally and in CI, and signing is skipped entirely when no key
        // is present — a contributor running `build` should not need one.
        extensions.configure<SigningExtension> {
            val key = providers.gradleProperty("signingInMemoryKey").orNull
            val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
            isRequired = key != null
            if (key != null) {
                useInMemoryPgpKeys(key, password)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }

        // Central takes a zip of a Maven repository layout, uploaded to its
        // Portal API, rather than a `publish` to a URL. Staging locally first
        // means the exact bytes that will be uploaded can be inspected before
        // anything leaves the machine.
        // One shared directory across every module: Central takes a single
        // bundle containing all of them, not one upload per artifact.
        val stagingDir = rootProject.layout.buildDirectory.dir("central-staging")
        afterEvaluate {
            extensions.configure<PublishingExtension> {
                repositories.maven {
                    name = "centralStaging"
                    url = uri(stagingDir)
                }
            }
        }

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

// ---- Maven Central bundle ------------------------------------------------

val centralStage by tasks.registering {
    group = "publishing"
    description = "Stages every published module into build/central-staging."
    dependsOn(
        publishedModules.map { ":$it:publishAllPublicationsToCentralStagingRepository" },
    )
}

val centralBundle by tasks.registering(Zip::class) {
    group = "publishing"
    description = "Builds the zip to upload to the Maven Central Portal."
    dependsOn(centralStage)

    val staging = layout.buildDirectory.dir("central-staging")
    from(staging)
    destinationDirectory.set(layout.buildDirectory.dir("central"))
    // kweryVersion rather than rootProject.version: the version is set on the
    // subprojects, so the root project's is still "unspecified".
    archiveFileName.set("kwery-$kweryVersion-bundle.zip")

    // Captured at configuration time. Reaching for `project` or a script
    // reference inside doLast breaks the configuration cache, which this build
    // has enabled — the failure is a serialization error rather than anything
    // that names the real cause.
    val signatureCount = providers.provider {
        staging.get().asFile.walkTopDown().count { it.isFile && it.extension == "asc" }
    }

    doLast {
        // A bundle without signatures is rejected by the Portal after upload,
        // which is a slow way to find out. Say so here instead.
        val count = signatureCount.get()
        if (count == 0) {
            logger.warn(
                "\ncentralBundle: no .asc signatures in the bundle. Maven Central " +
                    "will reject it.\nPass -PsigningInMemoryKey=... to sign; see " +
                    "CONTRIBUTING.md.",
            )
        } else {
            logger.lifecycle("centralBundle: $count signatures included.")
        }
    }
}
