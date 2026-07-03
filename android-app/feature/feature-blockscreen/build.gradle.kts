plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Compose UI: the shared "blocked" lock screen shown when a block fires. Owner: A-BLOCKSCREEN.
// Hosted either by blocking-overlay (SYSTEM_ALERT_WINDOW overlay) or BlockActivity (full-screen);
// kept a pure Compose surface here so both hosts render the same UI. Branded via core-common theme.

android {
    namespace = "com.flint.peakfocus.feature.blockscreen"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
