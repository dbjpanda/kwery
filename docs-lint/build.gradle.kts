import org.gradle.api.tasks.PathSensitivity

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Checks that every Kwery identifier used in docs/*.md actually exists. " +
    "Not published; exists so documentation cannot drift from the public API."

dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
}

tasks.withType<Test>().configureEach {
    // The lint reads the repo's markdown and .api dumps.
    systemProperty("kwery.repoRoot", rootDir.absolutePath)

    // Declare them as inputs, or Gradle's up-to-date check skips the task when
    // only a doc changed — which is the only time it has anything to say. Found
    // by injecting a deliberately broken identifier and watching the lint stay
    // green because it never ran.
    inputs.files(fileTree(rootDir.resolve("docs")) { include("**/*.md") })
        .withPropertyName("docs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Scoped to the api/ directories rather than a tree over rootDir: a
    // repo-wide scan sweeps in other modules' build outputs and Gradle rightly
    // refuses to run a task that reads them without declaring a dependency.
    inputs.files(
        listOf("kwery-core", "kwery-test", "kwery-persist", "kwery-android", "kwery-compose")
            .map { fileTree(rootDir.resolve("$it/api")) },
    ).withPropertyName("apiDumps").withPathSensitivity(PathSensitivity.RELATIVE)
}
