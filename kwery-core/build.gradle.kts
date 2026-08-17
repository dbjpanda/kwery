plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Kwery core: cache, observers, retries, mutations. Pure Kotlin/JVM, no Android dependencies (AD-1)."

dependencies {
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
