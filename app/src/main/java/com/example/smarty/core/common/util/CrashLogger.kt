package com.example.smarty.core.common.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.auth.FirebaseAuth

/**
 * Catches uncaught exceptions and writes them to a file in app-specific storage.
 * Also reports non-fatal exceptions and logs to Firebase Crashlytics.
 *
 * Location: /storage/emulated/0/Android/data/com.example.smarty/files/crash_log.txt
 * 
 * ENHANCED (v3.2.2):
 * - User context (userId, email)
 * - Device info (model, OS version)
 * - App version info
 * - Custom keys for filtering
 */
class CrashLogger(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            
            // Add user context
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null) {
                    crashlytics.setUserId(user.uid)
                    crashlytics.setCustomKey("user_email", user.email ?: "unknown")
                }
            } catch (ignored: Exception) {}
            
            // Add device context
            crashlytics.setCustomKey("device_model", android.os.Build.MODEL)
            crashlytics.setCustomKey("device_os_version", android.os.Build.VERSION.RELEASE)
            crashlytics.setCustomKey("device_manufacturer", android.os.Build.MANUFACTURER)
            
            // Add app context
            crashlytics.setCustomKey("app_version", getAppVersion(context))
            crashlytics.setCustomKey("thread_name", t.name)
            crashlytics.setCustomKey("thread_id", t.id)
            
            // Report to Firebase Crashlytics
            crashlytics.recordException(e)

            val sw = StringWriter()
            val pw = PrintWriter(sw)
            e.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logContent = "\n\n--- CRASH REPORT $timestamp ---\n" +
                    "Thread: ${t.name}\n" +
                    "Exception: ${e.javaClass.simpleName}\n" +
                    "Message: ${e.message}\n" +
                    "Stack Trace:\n$stackTrace\n" +
                    "Device: ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})\n" +
                    "App Version: ${getAppVersion(context)}\n" +
                    "-----------------------------\n"

            writeLog(logContent)

            Log.e("CrashLogger", "Uncaught exception captured", e)
        } catch (innerException: Exception) {
            Log.e("CrashLogger", "Failed to write crash log", innerException)
        } finally {
            // Pass to default handler to let the app crash naturally (or system handle it)
            defaultHandler?.uncaughtException(t, e)
        }
    }

    /**
     * Record a non-fatal exception to Crashlytics
     */
    fun recordNonFatal(exception: Throwable) {
        try {
            FirebaseCrashlytics.getInstance().recordException(exception)
            Log.w("CrashLogger", "Non-fatal exception recorded", exception)
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to record non-fatal exception", e)
        }
    }

    fun log(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val logMessage = "[$timestamp] $message"

            // Log to Firebase Crashlytics breadcrumbs
            FirebaseCrashlytics.getInstance().log(logMessage)

            writeLog("$logMessage\n")
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to write log", e)
        }
    }

    private fun writeLog(content: String) {
        val file = File(context.getExternalFilesDir(null), "crash_log.txt")
        file.appendText(content)
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    companion object {
        fun init(context: Context) {
            // Enable Crashlytics collection
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            Thread.setDefaultUncaughtExceptionHandler(CrashLogger(context))
        }

        fun log(context: Context, message: String) {
             try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val logMessage = "[$timestamp] $message"

                // Log to Firebase Crashlytics breadcrumbs
                FirebaseCrashlytics.getInstance().log(logMessage)

                val file = File(context.getExternalFilesDir(null), "crash_log.txt")
                file.appendText("$logMessage\n")
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        /**
         * Set custom key for crash reporting
         */
        fun setCustomKey(key: String, value: String) {
            try {
                FirebaseCrashlytics.getInstance().setCustomKey(key, value)
            } catch (e: Exception) {
                Log.e("CrashLogger", "Failed to set custom key", e)
            }
        }
        
        /**
         * Set custom key for crash reporting (Int value)
         */
        fun setCustomKey(key: String, value: Int) {
            try {
                FirebaseCrashlytics.getInstance().setCustomKey(key, value)
            } catch (e: Exception) {
                Log.e("CrashLogger", "Failed to set custom key", e)
            }
        }
    }
}
