plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Compose UI: screen-time reports from UsageStatsManager (per-app daily foreground time).
// Owner: A-STATS. Renders core-model.UsageStat rows. Queries UsageStatsManager.queryEvents
// directly (7-day, all-apps report needs event-level bucketing; core-data's UsageQuery and
// blocking-usagestats' DailyLimitTracker answer single-package/today questions and stay the
// source for limit enforcement). Aggregation/formatting is pure Kotlin — see src/test.

android {
    namespace = "com.flint.peakfocus.feature.stats"
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
    implementation(project(":permissions:permissions-special"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
