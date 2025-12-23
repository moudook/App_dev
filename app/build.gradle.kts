plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

android {
    namespace = "com.example.smarty"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.smarty"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Coil for image loading
    implementation(libs.coil.compose)

    // QR Code generation
    implementation(libs.zxing.core)

    // JSON serialization
    implementation(libs.gson)

    // Network
    implementation(libs.okhttp)

    // Security & DataStore (using maintained fork of deprecated JetSec library)
    implementation("dev.spght:encryptedprefs-ktx:1.1.1")
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

    // PDF text extraction
    implementation(libs.pdfbox.android)

    // Media3 ExoPlayer for audio playback
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // YouTube Player (IFrame-based, ToS compliant)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.1")

    // Markdown rendering for AI chat responses
    implementation("com.halilibo.compose-richtext:richtext-commonmark:0.17.0")
    implementation("com.halilibo.compose-richtext:richtext-ui-material3:0.17.0")

    // Vosk - Offline speech recognition for wake word detection
    implementation("com.alphacephei:vosk-android:0.3.75")

    // Koog AI Agent Framework
    implementation(libs.koog.agents)
    implementation(libs.koog.executor.google)
    implementation(libs.koog.executor.anthropic)
    implementation(libs.koog.executor.openai)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)

    // TOON (Token-Oriented Object Notation) - native implementation in util/toon/

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
}