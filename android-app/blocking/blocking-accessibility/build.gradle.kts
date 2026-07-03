plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.flint.peakfocus.blocking.accessibility"
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
    implementation(project(":core:core-data"))
    implementation(project(":blocking:blocking-engine"))
    // Shared enforcement seam: BlockScreenCoordinator + the branded Compose block surface.
    // All Compose stays inside blocking-overlay — this module needs no compose plugin.
    implementation(project(":blocking:blocking-overlay"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android) // service-scoped CoroutineScope
}
