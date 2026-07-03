plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.flint.peakfocus.blocking.overlay"
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
    // api: BlockCause/blockScreenState expose feature-blockscreen + core-model types
    // (BlockScreenReason/State, BreakLevel, Verdict) in this module's public seam API.
    api(project(":feature:feature-blockscreen"))
    api(project(":core:core-model"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-datastore"))   // FocusStateStore/LimitsStore snapshots
    implementation(project(":blocking:blocking-engine")) // Break/EmergencyPass/OpenLimit policies
    implementation(libs.androidx.core.ktx)
    // activity-compose also api-exposes androidx.savedstate (ComponentActivity IS a
    // SavedStateRegistryOwner), which BlockScreenHostView's ViewTree owner setters need —
    // the version catalog has no direct savedstate entry (catalog is A-SCAFFOLD-owned).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx) // LifecycleRegistry + ViewTree setter
    implementation(libs.kotlinx.coroutines.android)     // Dispatchers.Main for tickers/persists
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)           // Color.toArgb for the window background
    implementation(libs.androidx.material3)

    // JVM tests for the pure glue (decision→BlockScreenState mapping, exemption ledger).
    testImplementation(libs.junit)
}
