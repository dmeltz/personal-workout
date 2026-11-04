// Lets the pure-Kotlin core be built and tested on its own: `gradle -p core test`.
// Ignored when building from the repository root.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Fallback mirror when Maven Central rate-limits.
        maven("https://plugins.gradle.org/m2/")
    }
}

rootProject.name = "core"
