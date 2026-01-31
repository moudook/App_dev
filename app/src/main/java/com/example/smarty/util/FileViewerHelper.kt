package com.example.smarty.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Helper class for safely opening files in external applications.
 * Handles the FileUriExposedException on Android 7.0+ by using FileProvider.
 */
object FileViewerHelper {

    private const val TAG = "FileViewerHelper"

    /**
     * Opens a file using the appropriate external application.
     * Handles both file:// and content:// URIs properly.
     */
    fun openFile(context: Context, uriString: String, mimeType: String?): Boolean {
        return try {
            val intent = createViewIntent(context, uriString, mimeType)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No app found to open file: $uriString", e)
            Toast.makeText(context, context.getString(com.example.smarty.R.string.no_app_found), Toast.LENGTH_SHORT).show()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception opening file: $uriString", e)
            Toast.makeText(context, context.getString(com.example.smarty.R.string.permission_denied_file), Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: $uriString", e)
            Toast.makeText(context, context.getString(com.example.smarty.R.string.unable_to_open_file), Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Creates an Intent to view a file.
     * Automatically handles file:// to content:// conversion using FileProvider.
     */
    fun createViewIntent(context: Context, uriString: String, mimeType: String?): Intent {
        val uri = getContentUri(context, uriString)
        val effectiveMimeType = mimeType ?: getMimeTypeFromUri(context, uri) ?: "*/*"

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Converts a URI string to a content:// URI if necessary.
     */
    fun getContentUri(context: Context, uriString: String): Uri {
        return when {
            uriString.startsWith("content://") -> {
                Uri.parse(uriString)
            }
            uriString.startsWith("file://") -> {
                val filePath = uriString.removePrefix("file://")
                val file = File(filePath)
                getFileProviderUri(context, file)
            }
            uriString.startsWith("/") -> {
                val file = File(uriString)
                getFileProviderUri(context, file)
            }
            else -> {
                val parsedUri = Uri.parse(uriString)
                if (parsedUri.scheme == "file") {
                    val file = File(parsedUri.path ?: "")
                    getFileProviderUri(context, file)
                } else {
                    parsedUri
                }
            }
        }
    }

    /**
     * Gets a content:// URI from FileProvider for a file.
     */
    private fun getFileProviderUri(context: Context, file: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "FileProvider couldn't handle path: ${file.absolutePath}", e)
                Uri.fromFile(file)
            }
        } else {
            Uri.fromFile(file)
        }
    }

    /**
     * Tries to determine the MIME type from a URI.
     */
    private fun getMimeTypeFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.getType(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Opens an image file with proper error handling.
     */
    fun openImage(context: Context, uriString: String, mimeType: String? = null): Boolean {
        return openFile(context, uriString, mimeType ?: "image/*")
    }

    /**
     * Opens a video file with proper error handling.
     */
    fun openVideo(context: Context, uriString: String, mimeType: String? = null): Boolean {
        return openFile(context, uriString, mimeType ?: "video/*")
    }

    /**
     * Opens an audio file with proper error handling.
     */
    fun openAudio(context: Context, uriString: String, mimeType: String? = null): Boolean {
        return openFile(context, uriString, mimeType ?: "audio/*")
    }

    /**
     * Opens a document/file with proper error handling.
     */
    fun openDocument(context: Context, uriString: String, mimeType: String? = null): Boolean {
        return openFile(context, uriString, mimeType ?: "*/*")
    }
}
