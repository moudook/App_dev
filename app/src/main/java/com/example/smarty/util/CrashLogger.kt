package com.example.smarty.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catches uncaught exceptions and writes them to a file in app-specific storage.
 * Useful for debugging crashes on devices where Logcat is not accessible.
 *
 * Location: /storage/emulated/0/Android/data/com.example.smarty/files/crash_log.txt
 */
class CrashLogger(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
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

    fun log(message: String) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            writeLog("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to write log", e)
        }
    }

    private fun writeLog(content: String) {
        val file = File(context.getExternalFilesDir(null), "crash_log.txt")
        file.appendText(content)
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashLogger(context))
        }

        fun log(context: Context, message: String) {
             try {
                val file = File(context.getExternalFilesDir(null), "crash_log.txt")
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("[$timestamp] $message\n")
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
