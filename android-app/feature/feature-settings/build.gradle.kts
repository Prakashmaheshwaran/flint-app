plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Compose UI: keep-blocking-alive settings. Renders blocking-resilience's health snapshot
// (PermissionHealthChecker) as per-grant rows with user-initiated fix actions, requests the
// battery exemption, shows static per-OEM auto-start guidance (keyed by core-common.OemUtil),
// and explains recent process deaths (ExitReasonReporter) in plain language. Owner: A-SETTINGS.
// All row/guidance/diagnostics mapping is pure Kotlin — see src/test. Not the prominent-
// disclosure consent screen — that lives in feature-onboarding (Play gate, ADR-007); this screen
// only re-states that disclosure before any hand-off and never auto-redirects.

android {
    namespace = "com.flint.peakfocus.feature.settings"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-history"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":permissions:permissions-special"))
    implementation(project(":blocking:blocking-resilience"))
    implementation(project(":blocking:blocking-engine")) // supported-browser matrix and coverage states
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    testImplementation(libs.junit)
    testDebugImplementation(libs.androidx.junit)
    testDebugImplementation(libs.androidx.ui.test.junit4)
    testDebugImplementation(libs.robolectric)
}
