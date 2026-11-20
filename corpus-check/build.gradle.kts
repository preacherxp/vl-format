// Development tool, not shipped: formats real source trees and checks the
// result with the real Kotlin and Java parsers. See README "Development".
plugins {
    java
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":core"))
    // The Kotlin compiler's own parser (PSI), used as an independent oracle.
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.20")
}

application {
    mainClass.set("dev.vlformat.corpus.CorpusCheck")
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}
