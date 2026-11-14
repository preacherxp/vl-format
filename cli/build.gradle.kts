plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.vlformat.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

// Fat jar for standalone distribution: java -jar vl-format.jar
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("vl-format")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.vlformat.cli.MainKt",
            "Implementation-Title" to "vl-format",
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
