plugins {
    // Resolves and provisions the Java toolchain declared in build.gradle.kts when it is not installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "api-core"
