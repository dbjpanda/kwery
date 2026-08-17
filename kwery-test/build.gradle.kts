plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "TestQueryClient: virtual clock, request recording, controllable focus and connectivity."

dependencies {
    api(project(":kwery-core"))
    api(libs.kotlinx.coroutines.test)

    testImplementation(kotlin("test"))
}
