import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Play Store upload signing. The keystore and its passwords are local secrets: they live in
// `android-app/keystore.properties` (git-ignored) and are created by
// `scripts/setup-android-upload-key.sh` (via `make android-upload-key`). Without that file the
// release build keeps the legacy development key, so preview APKs (`make android-release`, the
// GitHub Releases workflow) still assemble on any machine and in CI.
val uploadKeystorePropertiesFile = rootProject.file("keystore.properties")
val uploadKeystoreProperties = Properties().apply {
    if (uploadKeystorePropertiesFile.isFile) {
        uploadKeystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasUploadKeystore = uploadKeystorePropertiesFile.isFile

android {
    namespace = "com.flint.peakfocus"
    // Play requires new apps and updates to target API 36 (Android 16) from 2026-08-31.
    compileSdk = 36

    defaultConfig {
        applicationId = "app.flint.focus"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasUploadKeystore) {
            create("upload") {
                storeFile = rootProject.file(
                    uploadKeystoreProperties.getProperty("storeFile")
                        ?: error("keystore.properties is missing storeFile"),
                )
                storePassword = uploadKeystoreProperties.getProperty("storePassword")
                    ?: error("keystore.properties is missing storePassword")
                keyAlias = uploadKeystoreProperties.getProperty("keyAlias")
                    ?: error("keystore.properties is missing keyAlias")
                keyPassword = uploadKeystoreProperties.getProperty("keyPassword")
                    ?: error("keystore.properties is missing keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Play uploads are signed with the local upload key when `keystore.properties`
            // exists. Otherwise this stays the legacy development-key preview build: release
            // code without the debug-only emulator tools, installable anywhere.
            signingConfig = if (hasUploadKeystore) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
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
