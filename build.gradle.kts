// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Detect if Android SDK is available (not available in Docker/CI server builds)
val hasAndroidSdk =
    try {
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
    // Removed: kotlin.android plugin - AGP built-in Kotlin handles this since AGP 9.0
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        verbose.set(false)
        android.set(true)
        ignoreFailures.set(true)
        enableExperimentalRules.set(false)
        filter {
            exclude("**/generated/**")
            exclude("**/build/**")
            exclude("**/test/**")
            exclude("**/androidTest/**")
        }
        additionalEditorconfig.set(
            mapOf(
                "ktlint_standard_value-parameter-comment" to "disabled",
                "ktlint_standard_value-argument-comment" to "disabled",
            ),
        )
    }
}

// Force Kotlin version across all dependencies to avoid version conflicts
subprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-common:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.3.21")
            force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.3.21")
            force("org.jetbrains.kotlin:kotlin-reflect:2.3.21")
        }
    }
}
