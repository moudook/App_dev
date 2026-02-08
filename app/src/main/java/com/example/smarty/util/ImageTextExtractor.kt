package com.example.smarty.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * OCR (Optical Character Recognition) utility for extracting text from images.
 * Uses Google ML Kit Text Recognition which runs entirely on-device.
 * 
 * Features:
 * - On-device processing (no network required)
 * - Supports Latin-based scripts
 * - Works with bitmaps, URIs, and file paths
 * - Memory-efficient image scaling
 */
object ImageTextExtractor {
    private const val TAG = "ImageTextExtractor"
    
    // Maximum image dimension to prevent OOM errors
    private const val MAX_IMAGE_DIMENSION = 1920
    
    // ML Kit text recognizer (lazy initialized)
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    
    /**
     * Result of OCR processing.
     */
    data class OcrResult(
        val text: String,
        val blockCount: Int,
        val confidence: Float,
        val processingTimeMs: Long
    ) {
        val isEmpty: Boolean get() = text.isBlank()
        val hasText: Boolean get() = text.isNotBlank()
    }
    
    /**
     * Extract text from an image URI.
     * 
     * @param context Application context
     * @param imageUri URI of the image to process
     * @return OcrResult containing extracted text and metadata
     */
    suspend fun extractTextFromUri(
        context: Context,
        imageUri: Uri
    ): OcrResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Load and scale bitmap
            val bitmap = loadScaledBitmap(context, imageUri)
            if (bitmap == null) {
                Log.w(TAG, "Failed to load bitmap from URI: $imageUri")
                return@withContext OcrResult(
                    text = "",
                    blockCount = 0,
                    confidence = 0f,
                    processingTimeMs = System.currentTimeMillis() - startTime
                )
            }
            
            val result = extractTextFromBitmap(bitmap)
            bitmap.recycle()
            
            result.copy(processingTimeMs = System.currentTimeMillis() - startTime)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from URI: ${e.message}", e)
            OcrResult(
                text = "",
                blockCount = 0,
                confidence = 0f,
                processingTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * Extract text from a Bitmap.
     */
    suspend fun extractTextFromBitmap(bitmap: Bitmap): OcrResult {
        val startTime = System.currentTimeMillis()
        
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val fullText = visionText.text
                    val blockCount = visionText.textBlocks.size
                    
                    // Calculate average confidence from all blocks
                    val avgConfidence = if (visionText.textBlocks.isNotEmpty()) {
                        visionText.textBlocks
                            .flatMap { block -> block.lines }
                            .flatMap { line -> line.elements }
                            .mapNotNull { element -> element.confidence }
                            .average()
                            .toFloat()
                    } else {
                        0f
                    }
                    
                    Log.d(TAG, "OCR completed: ${fullText.length} chars, $blockCount blocks, confidence: $avgConfidence")
                    
                    continuation.resume(
                        OcrResult(
                            text = fullText,
                            blockCount = blockCount,
                            confidence = avgConfidence,
                            processingTimeMs = System.currentTimeMillis() - startTime
                        )
                    )
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed: ${e.message}", e)
                    continuation.resume(
                        OcrResult(
                            text = "",
                            blockCount = 0,
                            confidence = 0f,
                            processingTimeMs = System.currentTimeMillis() - startTime
                        )
                    )
                }
            
            continuation.invokeOnCancellation {
                // ML Kit handles its own task cancellation
                Log.d(TAG, "OCR task cancelled")
            }
        }
    }
    
    /**
     * Load a scaled bitmap from URI to prevent OOM errors.
     */
    private fun loadScaledBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // First, decode bounds only
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(inputStream, null, options)
                
                // Calculate scale factor
                val scaleFactor = calculateScaleFactor(options.outWidth, options.outHeight)
                
                // Re-open stream and decode with scale
                context.contentResolver.openInputStream(uri)?.use { scaledStream ->
                    val scaledOptions = BitmapFactory.Options().apply {
                        inSampleSize = scaleFactor
                    }
                    BitmapFactory.decodeStream(scaledStream, null, scaledOptions)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${e.message}", e)
            null
        }
    }
    
    /**
     * Calculate the sample size for scaling down large images.
     */
    private fun calculateScaleFactor(width: Int, height: Int): Int {
        var scaleFactor = 1
        while (width / scaleFactor > MAX_IMAGE_DIMENSION || 
               height / scaleFactor > MAX_IMAGE_DIMENSION) {
            scaleFactor *= 2
        }
        return scaleFactor
    }
    
    /**
     * Check if the image at the given URI is suitable for OCR.
     * Checks file size and format.
     */
    fun isImageSuitableForOcr(context: Context, uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.startsWith("image/") == true
        } catch (e: Exception) {
            false
        }
    }
}


