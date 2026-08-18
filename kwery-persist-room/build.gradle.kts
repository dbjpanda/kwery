plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

description = "Room-backed persistence for large caches and long offline queues. " +
    "Row-level writes, so one changed entry does not rewrite everything."

dependencies {
    api(project(":kwery-persist"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
