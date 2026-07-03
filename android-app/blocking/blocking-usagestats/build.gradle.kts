plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.flint.peakfocus.blocking.usagestats"
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
    implementation(project(":blocking:blocking-engine"))
    // Path B enforcement handoff: every poll observation routes through PathBBlockHandoff
    // (shared coordinator → overlay / BlockActivity), same decision glue as Path A.
    implementation(project(":blocking:blocking-overlay"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
