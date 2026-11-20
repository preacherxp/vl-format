plugins {
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    // 17 = oldest LTS we support at runtime; keeps the fat jar runnable on
    // any reasonably modern JRE.
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "vl-format-core"
            pom {
                name.set("vl-format-core")
                description.set("Fast, extensible Kotlin & Java formatter — core library and rule SPI")
                url.set("https://github.com/${System.getenv("GITHUB_REPOSITORY") ?: "preacherxp/vl-format"}")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "preacherxp/vl-format"}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
