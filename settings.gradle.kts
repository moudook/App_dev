pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        // Vosk speech recognition repository
        maven("https://alphacephei.com/maven/")
    }
}

rootProject.name = "Friday"

// Detect if Android SDK is available
val hasAndroidSdk: Boolean = run {
    val localProps = file("local.properties")
    if (localProps.exists()) return@run true
    if (System.getenv("ANDROID_HOME") != null) return@run true
    if (System.getenv("ANDROID_SDK_ROOT") != null) return@run true
    false
}

// Only include Android app module when SDK is available
if (hasAndroidSdk) {
    include(":app")
}

include(":common")
include(":server")
 