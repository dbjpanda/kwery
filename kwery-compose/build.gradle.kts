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
        // Unset, this defaults to a very old platform, and the instrumentation
        // test APK then triggers Android's "built for an older version"
        // system dialog on launch — which steals foreground focus before
        // Compose can render, and fails every UI test with "no compose
        // hierarchies found" for a reason that has nothing to do with Compose.
        targetSdk = libs.versions.compileSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Compose's runtime logs composition errors through android.util.Log.
            // Without this the stub throws, and the RuntimeException it raises
            // replaces the actual composition failure in the report.
            isReturnDefaultValues = true
        }
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

    // Composition is driven by hand (see TestComposition) rather than through
    // Robolectric or an emulator: these bindings are an adapter over a Flow,
    // and everything worth asserting about them — resubscription, lambda
    // stability, observer detach — is composition behaviour, not rendering.
    testImplementation(project(":kwery-test"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)

    // What the JVM tests above cannot cover: what actually appears on screen
    // for each QueryState. That needs a real UI, not a headless composition.
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // ui-test-junit4 pulls espresso-core 3.5.0 hard, which reflects into
    // InputManager APIs that no longer exist on newer system images
    // (NoSuchMethodException on Android 17 emulators). Force the fix forward.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.foundation:foundation")
    androidTestImplementation(libs.androidx.activity.compose)
    androidTestImplementation(kotlin("test"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Supplies the empty host activity ui-test-junit4 launches into, since
    // this module is a library with no activity of its own.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
