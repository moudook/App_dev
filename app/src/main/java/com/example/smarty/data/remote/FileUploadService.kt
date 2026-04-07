package com.example.smarty.data.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class FileUploadService(
    private val client: HttpClient,
    private val remoteAgentService: RemoteAgentService, // To get token/url
    private val context: Context,
) {
    suspend fun uploadFile(
        uri: Uri,
        mimeType: String,
    ): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                // 1. Resolve content from URI to a temp file
                val tempFile = uriToTempFile(uri) ?: return@withContext UploadResult.Error("Could not process file")

                // 2. Prepare upload
                val fileName = tempFile.name
                val fileBytes = tempFile.readBytes()

                // 3. Upload using RemoteAgentService which handles Auth & URL
                val result =
                    remoteAgentService.uploadFile(
                        fileBytes = fileBytes,
                        fileName = fileName,
                        contentType = mimeType,
                    )

                if (result != null) {
                    UploadResult.Success(
                        url = result.toString(), // Assuming server returns URL string or JSON with URL
                        fileId = UUID.randomUUID().toString(), // Server should return this ideally
                        fileName = fileName,
                    )
                } else {
                    UploadResult.Error("Upload failed")
                }
            } catch (e: Exception) {
                Log.e("FileUploadService", "Upload failed", e)
                UploadResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun uriToTempFile(uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val tempFile = File.createTempFile("upload_", ".tmp", context.cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            Log.e("FileUploadService", "Failed to create temp file", e)
            null
        }
    }

    sealed class UploadResult {
        data class Success(val url: String, val fileId: String, val fileName: String) : UploadResult()

        data class Error(val message: String) : UploadResult()
    }
}
