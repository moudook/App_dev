package com.example.smarty.core.common.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

data class ShareableCategory(
    val id: String,
    val name: String,
    val description: String?,
    val notes: List<ShareableNote>
)

data class ShareableNote(
    val title: String,
    val content: String,
    val summary: String?,
    val sourceUrl: String?,
    val type: String,
    val whySaved: String?
)

object CategoryShareManager {
    private val gson = Gson()

    /**
     * Create a shareable JSON representation of a category and its notes
     */
    fun createShareableData(category: Category, notes: List<Note>): String {
        val shareableNotes = notes.map { note ->
            ShareableNote(
                title = note.title,
                content = note.content,
                summary = note.summary,
                sourceUrl = note.sourceUrl,
                type = note.type.name,
                whySaved = note.whySaved
            )
        }

        val shareable = ShareableCategory(
            id = category.id,
            name = category.name,
            description = category.description,
            notes = shareableNotes
        )

        return gson.toJson(shareable)
    }

    /**
     * Create a deep link for the category
     */
    fun createDeepLink(category: Category, notes: List<Note>): String {
        val data = createShareableData(category, notes)
        val encoded = Base64.getEncoder().encodeToString(data.toByteArray())
        return "smarty://import?data=$encoded"
    }

    /**
     * Generate QR code bitmap for a category (runs on background thread)
     */
    suspend fun generateCategoryQRCode(
        category: Category,
        notes: List<Note>,
        size: Int = 512
    ): Bitmap? {
        val deepLink = createDeepLink(category, notes)
        return QRCodeGenerator.generateQRCode(
            content = deepLink,
            size = size,
            foregroundColor = android.graphics.Color.parseColor("#050505"),
            backgroundColor = android.graphics.Color.parseColor("#FAFAFA")
        )
    }

    /**
     * Save QR code to cache and get shareable URI
     */
    fun saveQRCodeToCache(context: Context, bitmap: Bitmap, categoryName: String): Uri? {
        return try {
            val cachePath = File(context.cacheDir, "shared_qr")
            cachePath.mkdirs()
            val fileName = "Smarty_${categoryName.replace(" ", "_")}_qr.png"
            val file = File(cachePath, fileName)

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.e("CategoryShareManager", "Failed to save QR code to cache", e)
            null
        }
    }

    /**
     * Share category via Android's share sheet (integrates with Quick Share)
     * Runs QR code generation on background thread to avoid blocking main thread
     */
    suspend fun shareCategory(
        context: Context,
        category: Category,
        notes: List<Note>,
        includeQRCode: Boolean = true
    ) {
        val shareText = buildString {
            appendLine("Smarty Category: ${category.name}")
            appendLine("${notes.size} notes")
            appendLine()
            appendLine("Notes:")
            notes.take(5).forEach { note ->
                appendLine("- ${note.title}")
            }
            if (notes.size > 5) {
                appendLine("... and ${notes.size - 5} more")
            }
            appendLine()
            appendLine("Import link: ${createDeepLink(category, notes)}")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Smarty: ${category.name}")
            putExtra(Intent.EXTRA_TEXT, shareText)
        }

        // If QR code should be included, generate and attach it (runs on background thread)
        if (includeQRCode) {
            val qrBitmap = generateCategoryQRCode(category, notes)
            qrBitmap?.let { bitmap ->
                val uri = withContext(Dispatchers.IO) {
                    saveQRCodeToCache(context, bitmap, category.name)
                }
                uri?.let {
                    intent.type = "image/*"
                    intent.putExtra(Intent.EXTRA_STREAM, it)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

        val chooser = Intent.createChooser(intent, "Share ${category.name}")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /**
     * Parse imported category data
     */
    fun parseShareableData(jsonData: String): ShareableCategory? {
        return try {
            gson.fromJson(jsonData, ShareableCategory::class.java)
        } catch (e: Exception) {
            Log.e("CategoryShareManager", "Failed to parse shareable category data", e)
            null
        }
    }

    /**
     * Parse deep link and extract category data
     */
    fun parseDeepLink(uri: Uri): ShareableCategory? {
        return try {
            val encodedData = uri.getQueryParameter("data") ?: return null
            val jsonData = String(Base64.getDecoder().decode(encodedData))
            parseShareableData(jsonData)
        } catch (e: Exception) {
            Log.e("CategoryShareManager", "Failed to parse deep link", e)
            null
        }
    }
}
