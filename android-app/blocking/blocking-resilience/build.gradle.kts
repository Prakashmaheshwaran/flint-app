plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// OEM/battery survival: keep enforcement alive across reboots and process death (ADR-007).
// Owner: A-RESILIENCE — BootReceiver (re-arm after reboot), ExitReasonReporter
// (ApplicationExitInfo, API 30+), PermissionHealthChecker (detect a revoked a11y/overlay grant),
// TimeChangeReceiver (anti-bypass: clock/timezone-change guard feeding blocking-engine's
// pure ClockChangeGuard).

android {
    namespace = "com.flint.peakfocus.blocking.resilience"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:core-model"))
    implementation(project(":core:core-data"))                   // BlocklistStore — boot/time-change cache warm-up
    implementation(project(":core:core-datastore"))              // FocusStateStore — time-change re-keying target
    implementation(project(":blocking:blocking-engine"))         // ClockChangeGuard — the pure fail-closed policy
    implementation(project(":permissions:permissions-special"))  // grant checks aggregated into HealthStatus
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)                 // goAsync guard application (DataStore I/O)
    testImplementation(libs.junit)
}
