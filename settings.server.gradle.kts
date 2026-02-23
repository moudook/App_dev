# =============================================================================
# Server-only settings for fast Docker builds
# Use this when building only the server (no Android SDK needed)
# =============================================================================

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "smarty-server"

include(":common")
include(":server")