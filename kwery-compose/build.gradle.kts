plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

description = "Compose bindings: rememberQuery and friends. A thin adapter over " +
    "kwery-core's Flow surface, never a parallel implementation (AD-2)."

android {
    namespace = "dev.kwery.compose"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
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

    val composeBom = platform(libs.compose.bom)
    api(composeBom)
    api("androidx.compose.runtime:runtime")
    // For the lazy-list paging helper.
    implementation("androidx.compose.foundation:foundation")
}
