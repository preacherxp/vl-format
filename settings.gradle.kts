plugins {
    // Auto-provisions the JDK toolchain when the required version is missing.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "vl-format"

include(":core", ":cli", ":corpus-check")
