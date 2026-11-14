plugins {
    kotlin("jvm") version "2.2.20" apply false
}

// Release builds pass -PvlformatVersion=X.Y.Z (derived from the git tag).
val vlformatVersion = providers.gradleProperty("vlformatVersion").getOrElse("0.1.0-dev")

subprojects {
    group = "dev.vlformat"
    version = vlformatVersion

    repositories {
        mavenCentral()
    }
}
