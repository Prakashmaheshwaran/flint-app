import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Bundled policy and notices come from the canonical policy and resolved release graph.
apply(from = rootProject.file("gradle/offline-legal.gradle"))

// Owner-controlled upload credentials stay outside version control. Environment
// values take precedence over android-app/keystore.properties, including blanks
// (an accidentally empty CI secret must fail rather than select preview signing).
val uploadPropertiesFile = rootProject.file("keystore.properties")
val uploadProperties = Properties().apply {
    if (uploadPropertiesFile.isFile) uploadPropertiesFile.inputStream().use { load(it) }
}
val uploadFields = mapOf(
    "storeFile" to "FLINT_UPLOAD_STORE_FILE",
    "storePassword" to "FLINT_UPLOAD_STORE_PASSWORD",
    "keyAlias" to "FLINT_UPLOAD_KEY_ALIAS",
    "keyPassword" to "FLINT_UPLOAD_KEY_PASSWORD",
)
val uploadValues = uploadFields.mapValues { (property, environment) ->
    providers.environmentVariable(environment).orNull ?: uploadProperties.getProperty(property)
}
val uploadSigningRequested = uploadPropertiesFile.exists() || uploadValues.values.any { it != null }
if (uploadSigningRequested) {
    val missing = uploadFields.keys.filter { uploadValues[it].isNullOrBlank() }
    require(missing.isEmpty()) {
        "Incomplete upload signing configuration; provide all four upload fields. Missing: ${missing.joinToString()}."
    }
    require(rootProject.file(uploadValues.getValue("storeFile")!!).isFile) {
        "Upload keystore is missing; check FLINT_UPLOAD_STORE_FILE / storeFile."
    }
}
require(providers.environmentVariable("FLINT_REQUIRE_UPLOAD_SIGNING").orNull != "1" || uploadSigningRequested) {
    "Owner upload signing is required; development-signed previews cannot be submitted to Google Play."
}
if (!uploadSigningRequested) {
    logger.warn("Flint release: DEVELOPMENT-SIGNED PREVIEW (not a Google Play upload); no owner upload key configured.")
}

android {
    namespace = "com.flint.peakfocus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flint.peakfocus"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (uploadSigningRequested) {
            create("upload") {
                storeFile = rootProject.file(uploadValues.getValue("storeFile")!!)
                storePassword = uploadValues.getValue("storePassword")
                keyAlias = uploadValues.getValue("keyAlias")
                keyPassword = uploadValues.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No credentials preserves the existing preview path for public CI.
            // Incomplete credentials fail above; they never silently fall back.
            signingConfig = signingConfigs.getByName(if (uploadSigningRequested) "upload" else "debug")
            buildConfigField("boolean", "DEVELOPMENT_SIGNED_PREVIEW", (!uploadSigningRequested).toString())
            isMinifyEnabled = true
            isShrinkResources = true
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
    implementation(project(":core:core-history"))
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
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
