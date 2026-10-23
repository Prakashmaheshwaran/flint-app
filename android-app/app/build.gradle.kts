plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.flint.peakfocus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.flint.peakfocus"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            // Public previews use release code with the existing development signing key.
            // Debug-only emulator tools and dependencies must never enter those APKs.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar.jdk)
    implementation(project(":core:core-model"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-datastore"))
    implementation(project(":blocking:blocking-engine"))
    implementation(project(":blocking:blocking-accessibility"))
    implementation(project(":blocking:blocking-usagestats"))
    implementation(project(":blocking:blocking-overlay"))
    implementation(project(":blocking:blocking-resilience"))
    implementation(project(":permissions:permissions-special"))
    implementation(project(":feature:feature-onboarding"))
    implementation(project(":feature:feature-blocklist"))
    implementation(project(":feature:feature-stats"))
    implementation(project(":feature:feature-settings"))
    debugImplementation(project(":feature:feature-connected"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
