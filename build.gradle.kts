// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Detect if Android SDK is available (not available in Docker/CI server builds)
val hasAndroidSdk = try {
    val localProps = file("local.properties")
    if (localProps.exists()) {
        true
    } else {
        System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
    }
} catch (_: Exception) {
    false
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}

// Force Kotlin version across all dependencies to avoid version conflicts
subprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.1.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-common:2.1.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.1.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.21")
            force("org.jetbrains.kotlin:kotlin-reflect:2.1.21")
        }
    }
}