plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.flint.peakfocus.core.common"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Robolectric renders the flame at real sizes; it needs merged resources + manifest.
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    // Preview annotations must compile in every variant; the inspector stays debug-only
    // (matches feature-blocklist's split). Release previously failed on @Preview.
    implementation(libs.androidx.ui.tooling.preview)

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // Host render harness for deterministic flame pixels (no device required).
    testDebugImplementation(libs.robolectric)
}
