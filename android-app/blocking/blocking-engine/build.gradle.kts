plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-Kotlin decision core. No Android dependencies — both detection paths call into this so
// verdicts are identical regardless of which fired. Trivially unit-testable.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":core:core-model"))
    testImplementation(libs.junit)
}
