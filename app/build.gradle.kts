plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.google.services)
    // Crashlytics for crash reporting
    alias(libs.plugins.firebase.crashlytics)
    id("kotlin-parcelize")
}

android {
    namespace = "com.example.smarty"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.smarty"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "3.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../friday-release-key.keystore")
            // Security: Require environment variables for sensitive credentials
            // Never hardcode passwords or aliases in build files
            storePassword = System.getenv("FRIDAY_STORE_PASSWORD")
            keyAlias = System.getenv("FRIDAY_KEY_ALIAS")
            keyPassword = System.getenv("FRIDAY_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 minification is ENABLED with comprehensive ProGuard rules
            // Rules configured for: Firebase, Ktor SSE, WorkManager, Room,
            // EncryptedSharedPreferences, Kotlinx Serialization, PDFBox, Media3
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Upload ProGuard mapping to Crashlytics
            configure<com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension> {
                mappingFileUploadEnabled = true
            }
            // Apply the release signing config
            signingConfig = signingConfigs.getByName("debug")
            // Production Server URL - Configure via environment variable or update before build
            buildConfigField(
                "String",
                "SERVER_URL",
                System.getenv("SMARTY_SERVER_URL")?.let { "\"$it\"" } ?: "\"https://your-space-name.hf.space\"",
            )
        }
        debug {
            // Keep debug builds fast - no minification
            isMinifyEnabled = false
            // Debug Server URL - Configure via environment variable
            // Examples:
            //   Local: export SMARTY_SERVER_URL="http://10.0.2.2:7860"
            //   Ngrok: export SMARTY_SERVER_URL="https://your-ngrok-url.ngrok-free.dev"
            buildConfigField("String", "SERVER_URL", System.getenv("SMARTY_SERVER_URL")?.let { "\"$it\"" } ?: "\"http://10.0.2.2:7860\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            // Fix: Netty duplicate file conflict from Koog/Ktor dependencies
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    // APK Size Optimization: ABI Splits
    // Ship only arm64-v8a for Play Store (covers 95%+ of active devices)
    // This reduces APK size by 8-12MB compared to universal APK
    splits {
        abi {
            // Enable ABI splits for release builds
            isEnable = true
            // Reset to clear default ABIs
            reset()
            // Include only arm64-v8a (64-bit ARM devices)
            // This covers most modern Android devices
            include("arm64-v8a")
            // Set to false for Play Store (they'll serve correct APK)
            // Set to true if distributing APK directly outside Play Store
            isUniversalApk = false
        }
    }

    // Android App Bundle optimization for Play Store
    bundle {
        // Enable language split for smaller downloads
        language {
            enableSplit = true
        }
        // Enable density split for smaller downloads
        density {
            enableSplit = true
        }
        // Enable ABI split for smaller downloads
        abi {
            enableSplit = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material) // XML Material Components for BottomSheetBehavior
    implementation(libs.google.fonts)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // Paging3 for large list pagination
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Coil for image loading
    implementation(libs.coil.compose)
    // Palette for color extraction
    implementation(libs.androidx.palette.ktx)

    // QR Code generation
    implementation(libs.zxing.core)

    // JSON serialization
    implementation(libs.gson)

    // Network
    implementation(libs.okhttp)

    // Security & DataStore (using maintained fork of deprecated JetSec library)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.datastore.preferences)

    // Google Sign-In and Drive API for backup
    implementation(libs.play.services.auth)
    implementation(libs.google.api.services.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.client.android) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.http.client.gson)

    // WorkManager for scheduled backups
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.javax.inject)

    // PDF text extraction (Removed - Server side)
    // implementation(libs.pdfbox.android)

    // Media3 ExoPlayer for audio playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // REMOVED: YouTube Player - Not used in codebase (BATCH-08 optimization)
    // implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.1")

    // Markdown rendering for AI chat responses
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.17.0")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.17.0")

    // LaTeX rendering via WebView + KaTeX (using AndroidView - no extra dependency needed)

    // Vosk - Offline speech recognition for wake word detection (Values removed for Cloud Migration)
    // implementation("com.alphacephei:vosk-android:0.3.75")

    // ML Kit Text Recognition (Removed - Server side)
    // implementation("com.google.mlkit:text-recognition:16.0.1")

    // Koog AI Agent Framework (Removed - Migrated to Server)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Firebase - Only auth is actively used (BATCH-10 optimization)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)

    // Shared Common Module
    implementation(project(":common"))

    // REMOVED: Firestore - migrated to server sync (Phase 4)
    // REMOVED: gRPC dependencies - only needed for Firestore

    // Crashlytics for crash reporting
    implementation(libs.firebase.crashlytics)
    // FCM for Push Notifications
    implementation(libs.firebase.messaging)
    // Analytics not implemented - no logEvent() calls
    // implementation(libs.firebase.analytics)
    // Remote Config not implemented - no fetchAndActivate() calls
    // implementation(libs.firebase.config)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("org.robolectric:robolectric:4.14.1")

    // Android Instrumented Testing
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation("io.mockk:mockk-android:1.13.9")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // LeakCanary - Memory leak detection (debug only)
    debugImplementation(libs.leakcanary)
}

// Detekt configuration
detekt {
    ignoreFailures = true
}


