plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// android/VERSION is the single source of the version: 0.5.0 -> versionName "0.5.0", versionCode 500.
// The code only grows with the version, so every release installs over the previous one.
val appVersion = rootProject.file("VERSION").readText().trim().removePrefix("v")
val appVersionCode = appVersion.split(".").map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "il.yishai.parking"
    compileSdk = 35
    defaultConfig {
        applicationId = "il.yishai.parking"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        // Fixed key committed to the repo so every CI build installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // Unit tests run on the JVM: android.* calls (e.g. Log) return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        htmlReport = true
        // The app is sideloaded for one user; Play-only policy checks do not apply.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    // Bundle the synced GIS snapshot and rules config as assets (zones.json, garages.json, nightLots.json, rules.json).
    sourceSets["main"].assets.srcDirs("src/main/assets", "../../data", "../../config")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.car.app:app:1.4.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
