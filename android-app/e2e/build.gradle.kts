import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

// Share the app's SDK convention, including lane E's API 36 update.
evaluationDependsOn(":app")
val targetApp = project(":app").extensions.getByType<ApplicationExtension>()

android {
    namespace = "com.flint.peakfocus.test"
    compileSdk = targetApp.compileSdk
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    defaultConfig {
        minSdk = targetApp.defaultConfig.minSdk
        targetSdk = targetApp.defaultConfig.targetSdk
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main") {
        manifest.srcFile("../app/src/androidTest/AndroidManifest.xml")
        java.srcDir("../app/src/androidTest/kotlin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
