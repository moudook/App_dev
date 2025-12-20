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
