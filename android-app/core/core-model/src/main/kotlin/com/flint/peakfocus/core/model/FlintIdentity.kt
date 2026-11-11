package com.flint.peakfocus.core.model

/**
 * Flint's Android install identity (ADR-008, re-keyed 2026-11-11).
 *
 * The installed package is [APPLICATION_ID] for debug and release builds, while the Gradle
 * namespace and every Kotlin package keep `com.flint.peakfocus`. A component therefore has
 * the package [APPLICATION_ID] and a class under `com.flint.peakfocus`, so runtime code and
 * scripts never build a class name from `packageName` or use `pkg/.Class` shorthand against
 * the installed package. (Manifest `.Class` names resolve against the namespace and are fine.)
 *
 * Prefer `Context.packageName` at runtime. [APPLICATION_ID] exists for code without a
 * context (pure policy floors); an app unit test pins both values to the build.
 */
object FlintIdentity {
    /** Must equal `applicationId` in android-app/app/build.gradle.kts. */
    const val APPLICATION_ID = "app.flint.focus"

    /** Fully qualified class of the single launcher Activity (Kotlin namespace, not the package). */
    const val MAIN_ACTIVITY_CLASS = "com.flint.peakfocus.MainActivity"
}
