# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ============================================================================
# SECURITY: Strip debug/verbose logs in release builds
# This prevents sensitive data from leaking via logcat
# ============================================================================

# Remove all Log.d() (debug) calls - these may contain user queries, note titles
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

# Remove all logging that might contain sensitive data
-assumenosideeffects class android.util.Log {
    public static int d(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
}

# ============================================================================
# Keep line numbers for debugging (crash reports)
# ============================================================================
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

# ============================================================================
# OPTIMIZATION: Keep new utility classes for reflection/callbacks
# ============================================================================

# TokenManager - Keep for SharedPreferences and Flow
-keep class com.example.smarty.util.TokenManager { *; }
-keep class com.example.smarty.util.TokenState { *; }
-keepclassmembers class com.example.smarty.util.TokenState {
    public static ** INSTANCE;
    public static ** Companion;
}

# PdfChunker - Keep for PDF processing
-keep class com.example.smarty.util.PdfChunker { *; }
-keep class com.example.smarty.util.PDFTextExtractor { *; }
-keep class com.example.smarty.data.model.DocumentChunk { *; }
-keep class com.example.smarty.util.PDFChunkedResult { *; }
-keepclassmembers class com.example.smarty.util.PDFChunkedResult {
    public static ** INSTANCE;
    public static ** Companion;
}

# FCM Service - Keep for Firebase and serialization
-keep class com.example.smarty.service.FCMService { *; }
-keepclassmembers class com.example.smarty.service.FCMService {
    public static ** INSTANCE;
    public static ** Companion;
}

# ============================================================================
# Missing optional dependencies - dontwarn for R8
# ============================================================================

# Jackson (optional for OpenTelemetry)
-dontwarn com.fasterxml.jackson.**

# PDFBox JP2/JPEG2000 (optional image format)
-dontwarn com.gemalto.jp2.**

# Reactor/Micrometer context propagation (optional)
-dontwarn io.micrometer.context.**
-dontwarn reactor.blockhound.**

# Netty optional dependencies
-dontwarn io.netty.handler.codec.compression.**
-dontwarn io.netty.handler.ssl.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.resolver.dns.**
-dontwarn io.netty.util.internal.logging.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn com.jcraft.jzlib.**
-dontwarn com.ning.compress.**
-dontwarn net.jpountz.**
-dontwarn lzma.sdk.**

# Apache commons and logging (optional)
-dontwarn org.apache.commons.logging.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn commons.logging.**
-dontwarn org.apache.http.impl.auth.**

# Eclipse Jetty ALPN/NPN (optional for HTTP/2)
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**

# GSS/Kerberos (optional authentication)
-dontwarn org.ietf.jgss.**

# Conscrypt (optional SSL provider)
-dontwarn org.conscrypt.**

# BouncyCastle (optional crypto)
-dontwarn org.bouncycastle.**

# OpenTelemetry exporters
-dontwarn io.opentelemetry.exporter.**

# Azul CA (optional certificate authority)
-dontwarn com.azul.crs.client.**

# SLF4J implementations (optional logging backends)
-dontwarn org.slf4j.impl.**

# Tom Roush PDFBox Android
-dontwarn com.tom_roush.pdfbox.**

# Sun JNA
-dontwarn com.sun.jna.**

# Netty platform-specific (epoll, kqueue, io_uring - Linux/macOS only)
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.channel.kqueue.**
-dontwarn io.netty.incubator.channel.uring.**

# Lettuce Redis client (optional dependencies)
-dontwarn io.lettuce.core.**
-dontwarn org.HdrHistogram.**
-dontwarn org.LatencyUtils.**

# OpenTelemetry incubator/experimental APIs
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn io.opentelemetry.sdk.**

# Java EE / Jakarta EE (optional)
-dontwarn javax.enterprise.**
-dontwarn javax.naming.**

# JDK JFR (Java Flight Recorder - not on Android)
-dontwarn jdk.jfr.**
-dontwarn jdk.net.**

# JDK internal (not on Android)
-dontwarn jdk.internal.**
