package com.example.smarty.ui.components.viewers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.smarty.ui.components.OrganicThinkingIndicator
import java.io.File
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
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

    // State - must use remember to retain across recompositions!
    var pdfRenderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fileDescriptor by remember { mutableStateOf<ParcelFileDescriptor?>(null) } // BUG-014: Track for cleanup
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var totalPages by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Zoom and pan state
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val minScale = 1f
    val maxScale = 5f

    // Initialize PDF renderer - only when composable is active
    LaunchedEffect(documentUri) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing PdfRenderer for: $documentUri")
                val fd = getFileDescriptor(context, documentUri)
                if (fd != null) {
                    fileDescriptor = fd // BUG-014: Store for cleanup
                    val renderer = PdfRenderer(fd)
                    pdfRenderer = renderer
                    totalPages = renderer.pageCount
                    Log.d(TAG, "PdfRenderer created successfully. Pages: $totalPages")
                    isLoading = false
                } else {
                    Log.e(TAG, "FileDescriptor is null for: $documentUri")
                    errorMessage = context.getString(R.string.error_opening_pdf)
                    isLoading = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception creating PdfRenderer: ${e.message}", e)
                errorMessage = context.getString(R.string.error_opening_pdf)
                isLoading = false
            }
        }
    }

    // Reset zoom when page changes
    LaunchedEffect(currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    // Render current page - lazy, only when needed
    // BUG-046: Memory-adaptive scaling based on device capabilities
    // BUG-047: Fixed race condition when rapidly switching pages
    // - Added debounce delay to prevent rapid re-renders
    // - Added mutex to ensure only one page is rendered at a time
    // - PdfRenderer.openPage() throws if a page is already open
    LaunchedEffect(currentPage, isLoading) {
        Log.d(TAG, "Render LaunchedEffect triggered: currentPage=$currentPage, isLoading=$isLoading, pdfRenderer=${pdfRenderer != null}")
        if (isLoading) return@LaunchedEffect

        // BUG-047: Debounce rapid page changes - wait 150ms before rendering
        // This allows the user to swipe through pages quickly without triggering
        // expensive renders for each intermediate page
        kotlinx.coroutines.delay(150)

        pdfRenderer?.let { renderer ->
            Log.d(TAG, "Starting page render: page=$currentPage, totalPages=${renderer.pageCount}")
            withContext(Dispatchers.IO) {
                try {
                    if (currentPage < renderer.pageCount) {
                        // Store old bitmap reference for safe recycling after new bitmap is set
                        val oldBitmap = pageBitmap

                        // BUG-047: Synchronized page access - PdfRenderer only allows one page open at a time
                        // Use synchronized block to prevent concurrent page operations
                        val newBitmap = synchronized(renderer) {
                            // Double-check we still want this page (may have changed during debounce)
                            if (currentPage >= renderer.pageCount) return@synchronized null

                            val page = try {
                                renderer.openPage(currentPage)
                            } catch (e: IllegalStateException) {
                                // Page already open or renderer closed - skip this render
                                Log.w(TAG, "Could not open page $currentPage: ${e.message}")
                                return@synchronized null
                            }

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

                            val bitmap = try {
                                Bitmap.createBitmap(
                                    finalWidth,
                                    finalHeight,
                                    Bitmap.Config.ARGB_8888
                                )
                            } catch (e: OutOfMemoryError) {
                                Log.e(TAG, "OOM creating bitmap, trying smaller size")
                                page.close()
                                // Try with original size as fallback
                                Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
                            }

                            try {
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            } finally {
                                page.close() // Always close the page
                            }

                            Log.d(TAG, "PDF page rendered: ${pageWidth}x${pageHeight} -> ${finalWidth}x${finalHeight} (${requiredMB}MB)")
                            bitmap
                        }

                        // Only update bitmap if render succeeded
                        if (newBitmap != null) {
                            // Set new bitmap first
                            pageBitmap = newBitmap

                            // Recycle old bitmap after UI has had chance to update
                            kotlinx.coroutines.delay(100)
                            try {
                                oldBitmap?.let { bmp ->
                                    if (!bmp.isRecycled) {
                                        bmp.recycle()
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore - bitmap may already be recycled or in use
                            }
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    Log.e(TAG, "OOM rendering PDF page", e)
                    errorMessage = context.getString(R.string.not_enough_memory_to_render)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // BUG-047: Normal cancellation when user switches pages rapidly - don't log as error
                    Log.d(TAG, "Page render cancelled (user switched pages)")
                    throw e // Re-throw to properly cancel the coroutine
                } catch (e: Exception) {
                    Log.e(TAG, "Exception rendering PDF page: ${e.message}", e)
                    errorMessage = context.getString(R.string.error_rendering_page)
                }
            }
        }
    }

    // Cleanup - release all resources immediately (BUG-014: Comprehensive cleanup)
    DisposableEffect(Unit) {
        onDispose {
            try {
                // Safe bitmap recycling - check if not already recycled
                pageBitmap?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
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

    // Theme-aware background for better document contrast
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val headerColor = MaterialTheme.colorScheme.surface
    val contentColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        when {
            isLoading -> {
                OrganicThinkingIndicator(
                    modifier = Modifier.align(Alignment.Center)
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
                        tint = contentColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = errorMessage ?: stringResource(R.string.unknown_error),
                        color = contentColor,
                        textAlign = TextAlign.Center
                    )
                }
            }
            pageBitmap != null -> {
                val swipeThreshold = 100f // Minimum swipe distance to trigger page change

                // Zoomable and pannable PDF content with swipe navigation
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 56.dp, bottom = if (totalPages > 1) 64.dp else 0.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                // Wait for first finger down
                                awaitFirstDown(requireUnconsumed = false)

                                var zoom = 1f
                                var pan = Offset.Zero
                                var totalPanX = 0f
                                var isPinching = false

                                do {
                                    val event = awaitPointerEvent()
                                    val pointerCount = event.changes.size

                                    // Detect if this is a pinch gesture (2+ fingers)
                                    if (pointerCount >= 2) {
                                        isPinching = true
                                        zoom = event.calculateZoom()
                                        pan = event.calculatePan()

                                        // Apply zoom
                                        val newScale = (scale * zoom).coerceIn(minScale, maxScale)
                                        scale = newScale

                                        // Apply pan when zoomed
                                        if (scale > 1f) {
                                            val maxOffsetX = (size.width * (scale - 1)) / 2
                                            val maxOffsetY = (size.height * (scale - 1)) / 2
                                            offset = Offset(
                                                x = (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                                y = (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                            )
                                        }
                                    } else if (pointerCount == 1 && !isPinching) {
                                        // Single finger drag
                                        val change = event.changes.first()
                                        if (change.positionChanged()) {
                                            val dragAmount = change.position - change.previousPosition

                                            if (scale > 1.1f) {
                                                // When zoomed, pan the image
                                                val maxOffsetX = (size.width * (scale - 1)) / 2
                                                val maxOffsetY = (size.height * (scale - 1)) / 2
                                                offset = Offset(
                                                    x = (offset.x + dragAmount.x).coerceIn(-maxOffsetX, maxOffsetX),
                                                    y = (offset.y + dragAmount.y).coerceIn(-maxOffsetY, maxOffsetY)
                                                )
                                            } else {
                                                // When not zoomed, track for page swipe
                                                totalPanX += dragAmount.x
                                            }
                                        }
                                    }

                                    // Consume all changes
                                    event.changes.forEach { it.consume() }

                                } while (event.changes.any { it.pressed })

                                // Gesture ended - check for page swipe (only when not zoomed and wasn't pinching)
                                if (!isPinching && scale <= 1.1f) {
                                    if (totalPanX < -swipeThreshold && currentPage < totalPages - 1) {
                                        currentPage++ // Swipe left = next page
                                    } else if (totalPanX > swipeThreshold && currentPage > 0) {
                                        currentPage-- // Swipe right = previous page
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    // Toggle zoom on double tap
                                    if (scale > 1.5f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.pdf_page_description, currentPage + 1),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
            }
        }

        // Header with improved styling
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            color = headerColor,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = contentColor
                    )
                }

                Text(
                    text = fileName ?: stringResource(R.string.pdf_document),
                    color = contentColor,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )

                // Zoom indicator
                if (scale > 1f) {
                    Text(
                        text = stringResource(R.string.zoom_percentage, (scale * 100).toInt()),
                        color = contentColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }

        // Page navigation - compact pill style at bottom
        if (totalPages > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = headerColor,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                            contentDescription = stringResource(R.string.previous_page),
                            tint = if (currentPage > 0) contentColor else contentColor.copy(alpha = 0.3f)
                        )
                    }

                    Text(
                        text = stringResource(R.string.page_indicator, currentPage + 1, totalPages),
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    IconButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        enabled = currentPage < totalPages - 1,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                            contentDescription = stringResource(R.string.next_page),
                            tint = if (currentPage < totalPages - 1) contentColor else contentColor.copy(alpha = 0.3f)
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
    var textContent by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                errorMessage = context.getString(R.string.error_reading_file, e.message ?: "")
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
                        contentDescription = stringResource(R.string.close)
                    )
                }

                Text(
                    text = fileName ?: stringResource(R.string.text_document),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        when {
            isLoading -> {
                OrganicThinkingIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage ?: stringResource(R.string.unknown_error),
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
                    contentDescription = stringResource(R.string.close)
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
                text = fileName ?: stringResource(R.string.document),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.unsupported_preview),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (mimeType != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.file_type_label, mimeType),
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
