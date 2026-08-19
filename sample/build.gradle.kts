plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

description = "Sample app. Not published — it exists so documentation examples " +
    "have to actually compile against the real API."

android {
    namespace = "dev.kwery.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.kwery.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "0.1.0"
    }

    // Instrumentation runs against the MINIFIED build on purpose. Kwery encodes
    // enum key parts by `name`, and if R8 rewrote those names every persisted
    // key would change: the cache would miss on every cold start after release,
    // silently, in a way no debug build can reproduce.
    testBuildType = "release"

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            testProguardFiles("test-proguard-rules.pro")
            // Debug signing so the release variant is installable for tests.
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // No explicitApi() here: this is an app, not a published library.
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(libs.versions.jvmTarget.get()),
        )
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.runner)
    // Reached reflectively by AndroidJUnitRunner. Present transitively in a
    // normal build, absent here, so the minified test process died in onCreate
    // before running anything and reported zero tests rather than a failure.
    androidTestImplementation("androidx.tracing:tracing:1.2.0")
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)

    implementation(project(":kwery-core"))
    implementation(project(":kwery-android"))
    implementation(project(":kwery-compose"))
    // The two headline features need to be demonstrable, not just documented.
    implementation(project(":kwery-persist"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
