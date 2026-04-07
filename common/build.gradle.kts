// Detect if Android SDK is available (not available in Docker/CI server builds)
val hasAndroidSdk =
    try {
        val localProps = file("${rootProject.projectDir}/local.properties")
        if (localProps.exists()) {
            true
        } else {
            // Check if ANDROID_HOME or ANDROID_SDK_ROOT is set
            System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null
        }
    } catch (_: Exception) {
        false
    }

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
}

// Conditionally apply Android plugins only when SDK is available
if (hasAndroidSdk) {
    apply(plugin = "com.android.library")
    apply(plugin = "com.google.devtools.ksp")
}

kotlin {
    if (hasAndroidSdk) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            }
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // Room common provides pure annotation classes (@Entity, @PrimaryKey, etc.)
            // It's a pure Java library - no Android SDK required
            implementation(libs.androidx.room.common)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

if (hasAndroidSdk) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "com.example.smarty.common"
        compileSdk = 35
        defaultConfig {
            minSdk = 26
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    dependencies {
        // Apply Room compiler to Android target
        add("kspAndroid", libs.androidx.room.compiler)
    }
}
