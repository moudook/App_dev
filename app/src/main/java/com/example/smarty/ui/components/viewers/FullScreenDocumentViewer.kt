package com.example.smarty.ui.components.viewers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.example.smarty.util.ResourceManager

/**
 * Full-screen document viewer supporting PDFs and text files.
 *
 * OPTIMIZATION: Completely dormant until shown.
 * - PdfRenderer only created when Dialog opens
 * - Bitmap recycled immediately on dismiss
 * - No background threads when not visible
 * - Lazy page rendering (only current page)
 * - Minimal memory footprint
 *
 * Features:
 * - PDF viewing with page navigation
 * - Text file viewing
 */
@Composable
fun FullScreenDocumentViewer(
    documentUri: String,
    mimeType: String?,
    fileName: String?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        when {
            mimeType?.contains("pdf") == true -> {
                PdfViewerContent(
                    documentUri = documentUri,
                    fileName = fileName,
                    onDismiss = onDismiss
                )
            }
            mimeType?.startsWith("text/") == true -> {
                TextViewerContent(
                    documentUri = documentUri,
                    fileName = fileName,
                    onDismiss = onDismiss
                )
            }
            else -> {
                UnsupportedDocumentContent(
                    fileName = fileName,
                    mimeType = mimeType,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun PdfViewerContent(
    documentUri: String,
    fileName: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // State - only exists while viewer is open
    var pdfRenderer by mutableStateOf<PdfRenderer?>(null)
    var fileDescriptor by mutableStateOf<ParcelFileDescriptor?>(null) // BUG-014: Track for cleanup
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var totalPages by mutableStateOf(0)
    var pageBitmap by mutableStateOf<Bitmap?>(null)
    var isLoading by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)

    // Initialize PDF renderer - only when composable is active
    LaunchedEffect(documentUri) {
        withContext(Dispatchers.IO) {
            try {
                val fd = getFileDescriptor(context, documentUri)
                if (fd != null) {
                    fileDescriptor = fd // BUG-014: Store for cleanup
                    val renderer = PdfRenderer(fd)
                    pdfRenderer = renderer
                    totalPages = renderer.pageCount
                    isLoading = false
                } else {
                    errorMessage = "Could not open PDF file. The file may have been moved or deleted."
                    isLoading = false
                }
            } catch (e: Exception) {
                errorMessage = "Error opening PDF: ${e.message}"
                isLoading = false
            }
        }
    }

    // Render current page - lazy, only when needed
    // BUG-046: Memory-adaptive scaling based on device capabilities
    LaunchedEffect(currentPage, pdfRenderer) {
        pdfRenderer?.let { renderer ->
            withContext(Dispatchers.IO) {
                try {
                    if (currentPage < renderer.pageCount) {
                        // Recycle previous bitmap to free memory
                        pageBitmap?.recycle()

                        val page = renderer.openPage(currentPage)

                        // Calculate adaptive scale based on device capabilities (BUG-046)
                        val maxDimension = try {
                            ResourceManager.getMaxImageDimension()
                        } catch (e: Exception) {
                            2048 // Fallback
                        }

                        // Calculate scale to fit within max dimension while maintaining aspect ratio
                        val pageWidth = page.width
                        val pageHeight = page.height
                        val scaleFactor = minOf(
                            maxDimension.toFloat() / pageWidth,
                            maxDimension.toFloat() / pageHeight,
                            3f // Cap at 3x to prevent excessive memory usage
                        ).coerceAtLeast(1f) // At least 1x scale

                        val renderWidth = (pageWidth * scaleFactor).toInt()
                        val renderHeight = (pageHeight * scaleFactor).toInt()

                        // Check memory before allocating (BUG-046)
                        val requiredMB = (renderWidth.toLong() * renderHeight * 4) / (1024 * 1024)
                        val hasMemory = try {
                            ResourceManager.hasEnoughMemory(requiredMB)
                        } catch (e: Exception) {
                            true // Proceed if ResourceManager not initialized
                        }

                        // If not enough memory, reduce scale
                        val finalWidth: Int
                        val finalHeight: Int
                        if (!hasMemory) {
                            // Reduce to 1x if memory constrained
                            finalWidth = pageWidth
                            finalHeight = pageHeight
                            Log.w(TAG, "Reduced PDF scale due to memory pressure")
                        } else {
                            finalWidth = renderWidth
                            finalHeight = renderHeight
                        }

                        val bitmap = Bitmap.createBitmap(
                            finalWidth,
                            finalHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        pageBitmap = bitmap

                        Log.d(TAG, "PDF page rendered: ${pageWidth}x${pageHeight} -> ${finalWidth}x${finalHeight} (${requiredMB}MB)")
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM rendering PDF page", e)
                    errorMessage = "Not enough memory to render page. Try closing other apps."
                } catch (e: Exception) {
                    errorMessage = "Error rendering page: ${e.message}"
                }
            }
        }
    }

    // Cleanup - release all resources immediately (BUG-014: Comprehensive cleanup)
    DisposableEffect(Unit) {
        onDispose {
            try {
                pageBitmap?.recycle()
                pageBitmap = null
                pdfRenderer?.close()
                pdfRenderer = null
                fileDescriptor?.close() // BUG-014: Close file descriptor
                fileDescriptor = null
            } catch (e: Exception) {
                Log.w(TAG, "Error during PDF cleanup: ${e.message}")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.DarkGray)
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }
            errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage ?: "Unknown error",
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
            pageBitmap != null -> {
                Image(
                    bitmap = pageBitmap!!.asImageBitmap(),
                    contentDescription = "PDF page ${currentPage + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, bottom = 80.dp)
                )
            }
        }

        // Header - static
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = Color.Black.copy(alpha = 0.7f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                Text(
                    text = fileName ?: "PDF Document",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        // Page navigation - static controls
        if (totalPages > 1) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = "Previous page",
                            tint = if (currentPage > 0) Color.White else Color.Gray
                        )
                    }

                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    IconButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = "Next page",
                            tint = if (currentPage < totalPages - 1) Color.White else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextViewerContent(
    documentUri: String,
    fileName: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var textContent by mutableStateOf<String?>(null)
    var isLoading by mutableStateOf(true)
    var errorMessage by mutableStateOf<String?>(null)

    // Load text only when composable is active (BUG-014: Proper resource cleanup with use())
    LaunchedEffect(documentUri) {
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(documentUri)
                textContent = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().use { reader ->
                        reader.readText()
                    }
                }
                isLoading = false
            } catch (e: Exception) {
                errorMessage = "Error reading file: ${e.message}"
                isLoading = false
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            textContent = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close"
                    )
                }

                Text(
                    text = fileName ?: "Text Document",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        when {
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage ?: "Unknown error",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    textAlign = TextAlign.Center
                )
            }
            textContent != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = textContent ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun UnsupportedDocumentContent(
    fileName: String?,
    mimeType: String?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Close button
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close"
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = fileName ?: "Document",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This document type cannot be previewed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (mimeType != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Type: $mimeType",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

private const val TAG = "DocViewer"

/**
 * Gets a ParcelFileDescriptor for the given URI.
 * Includes proper error logging, file existence checks, and URI decoding.
 */
private fun getFileDescriptor(context: Context, uriString: String): ParcelFileDescriptor? {
    Log.d(TAG, "Opening file: $uriString")
    return try {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "content" -> {
                val fd = context.contentResolver.openFileDescriptor(uri, "r")
                if (fd == null) {
                    Log.e(TAG, "ContentResolver returned null for: $uriString")
                }
                fd
            }
            "file" -> {
                val path = uri.path ?: run {
                    Log.e(TAG, "URI path is null: $uriString")
                    return null
                }
                val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
                val file = File(decodedPath)
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist: $decodedPath")
                    return null
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            null -> {
                // Direct file path (no scheme)
                val decodedPath = java.net.URLDecoder.decode(uriString, "UTF-8")
                val file = File(decodedPath)
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist: $decodedPath")
                    return null
                }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            else -> {
                Log.e(TAG, "Unsupported URI scheme: ${uri.scheme}")
                // Try content resolver as fallback
                context.contentResolver.openFileDescriptor(uri, "r")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open file: $uriString", e)
        null
    }
}
