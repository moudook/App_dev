# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================================
# BUG-068 FIX: Preserve Gson serialization classes
# ============================================================================

# Keep Gson annotations
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep Gson TypeToken (used for generic types)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep data model classes used with Gson
-keep class com.example.smarty.data.model.** { *; }
-keep class com.example.smarty.data.backup.** { *; }

# Keep classes with @SerializedName annotations
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all data classes (Kotlin)
-keepclassmembers class * {
    public <init>(...);
}

# ============================================================================
# Room Database
# ============================================================================

# Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# ============================================================================
# Kotlin Coroutines
# ============================================================================

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ============================================================================
# OkHttp / Retrofit (if used)
# ============================================================================

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================================
# Keep AI Provider response classes
# ============================================================================

-keep class com.example.smarty.data.remote.providers.*.** { *; }
-keep class com.example.smarty.data.remote.AIResponse { *; }
-keep class com.example.smarty.data.remote.AIResponseParser { *; }

# ============================================================================
# Prevent stripping of security-related classes
# ============================================================================

-keep class com.example.smarty.util.PrivacyGuard { *; }
-keep class com.example.smarty.util.ContentSecurityFilter { *; }

# ============================================================================
# Vosk Speech Recognition
# ============================================================================

-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn org.vosk.**

# ============================================================================
# Koog AI Agent Framework
# ============================================================================

-keep class ai.koog.** { *; }
-keepclassmembers class ai.koog.** { *; }
-dontwarn ai.koog.**

# ============================================================================
# Kotlinx Serialization
# ============================================================================

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `Serializable` class members
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializers defined in companion objects
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================================
# Ktor Client
# ============================================================================

-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ============================================================================
# Media3 ExoPlayer
# ============================================================================

-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ============================================================================
# Compose - Keep Compose runtime classes
# ============================================================================

-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ============================================================================
# Coil Image Loading
# ============================================================================

-keep class coil.** { *; }
-dontwarn coil.**

# ============================================================================
# ZXing QR Code
# ============================================================================

-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ============================================================================
# PDFBox Android
# ============================================================================

-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**
-dontwarn org.bouncycastle.**
-dontwarn javax.activation.**

# ============================================================================
# Google APIs (Drive, Auth)
# ============================================================================

-keep class com.google.api.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.api.**
-dontwarn com.google.android.gms.**

# ============================================================================
# YouTube Player (IFrame)
# ============================================================================

-keep class com.pierfrancescosoffritti.** { *; }
-dontwarn com.pierfrancescosoffritti.**

# ============================================================================
# Richtext Markdown
# ============================================================================

-keep class com.halilibo.richtext.** { *; }
-dontwarn com.halilibo.richtext.**

# ============================================================================
# Encrypted Preferences
# ============================================================================

-keep class dev.spght.encryptedprefs.** { *; }
-dontwarn dev.spght.encryptedprefs.**

# ============================================================================
# DataStore
# ============================================================================

-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ============================================================================
# WorkManager
# ============================================================================

-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ============================================================================
# Keep app's voice package for Vosk callbacks
# ============================================================================

-keep class com.example.smarty.voice.** { *; }

# ============================================================================
# Keep agent package for reflection/callbacks
# ============================================================================

-keep class com.example.smarty.agent.** { *; }
