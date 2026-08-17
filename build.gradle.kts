plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

// The .api dumps are the public surface. Any change to them must appear as a
// reviewed diff, never as a side effect of an unrelated edit.
// NOTE: add "sample" to ignoredProjects when the sample app module lands.
apiValidation {
    ignoredProjects += emptyList<String>()
}

subprojects {
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
