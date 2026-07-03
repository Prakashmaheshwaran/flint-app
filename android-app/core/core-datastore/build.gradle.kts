plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Typed user-preference persistence (Jetpack DataStore). Sits alongside core-data's
// SharedPreferences/Room stores; the AccessibilityService still reads its hot path synchronously
// from core-data, while async/observable settings (theme, toggles, schedules) live here. ADR-002.

android {
    namespace = "com.flint.peakfocus.core.datastore"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // api: the store interfaces expose core-model types and kotlinx Flow in their signatures,
    // so consumers (feature-blocklist &c.) must see them on their compile classpath.
    api(project(":core:core-model"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)

    // JVM unit tests: codec round-trips + store behavior against a fake DataStore (no device).
    testImplementation(libs.junit)
}
