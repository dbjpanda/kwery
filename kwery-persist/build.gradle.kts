plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Persistence contracts and hydration. Serialization lives here so " +
    "kwery-core stays dependency-light."

dependencies {
    api(project(":kwery-core"))
    api(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
