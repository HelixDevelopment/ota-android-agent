// :core — PURE Kotlin/JVM library. NO Android plugin. Framework-independent OTA logic + DTOs.
// Compiles and tests with the system Gradle (9.5) via `gradle :core:test`.

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(17)
}
