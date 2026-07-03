plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-Kotlin module: data models shared across the app and the headless blocking engine.
// No Android dependencies so it (and blocking-engine) stay trivially unit-testable.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
