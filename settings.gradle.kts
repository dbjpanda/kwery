rootProject.name = "kwery"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

// JVM-pure modules. Android and Compose modules are added once these are green;
// kwery-core must never depend on them (AD-1).
include(":kwery-core")
include(":kwery-test")
