plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

description = "Android implementations of Kwery's environment contracts: " +
    "foreground detection and validated connectivity."

android {
    namespace = "dev.kwery.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The instrumentation tests are the only place some behaviour can be
    // checked at all: a real ConnectivityManager reports capabilities no JVM
    // fake can, and R8 only rewrites bytecode in a real build.
    testBuildType = "debug"

    buildTypes {
        debug {
            // Minified so the key-encoding test runs against code R8 has
            // actually processed. Encoding enum constants by `name` is only
            // safe if R8 keeps those names, and that cannot be checked on the
            // JVM.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    explicitApi()
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()),
        )
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":kwery-core"))
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.common)

    androidTestImplementation(kotlin("test"))
    androidTestImplementation(project(":kwery-persist"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
