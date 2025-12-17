plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}