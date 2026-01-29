package com.example.smarty.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.smarty.MainActivity
import com.example.smarty.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service for screen capture using MediaProjection.
 *
 * This service:
 * 1. Requests MediaProjection permission ONCE
 * 2. Stays running in background (START_STICKY)
 * 3. Captures screenshots instantly on demand
 * 4. Keeps VirtualDisplay ready for fast captures
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 9999

        @Volatile
        private var instance: ScreenCaptureService? = null

        private val captureMutex = Mutex()

        /**
         * Check if the service is running and has an active MediaProjection.
         */
        fun isReady(): Boolean = instance?.isProjectionReady?.get() == true

        /**
         * Request a screenshot capture - fast, no delays.
         * @return File path of the captured screenshot, or null if failed
         */
        suspend fun captureScreenshot(): String? {
            val service = instance ?: return null
            return service.captureScreen()
        }

        /**
         * Start the capture service with MediaProjection data.
         */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the capture service completely.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        private const val ACTION_START = "com.example.smarty.action.START_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // Track if projection is ready for instant capture
    private val isProjectionReady = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        getScreenMetrics()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode == Activity.RESULT_OK && data != null) {
                    startForeground(NOTIFICATION_ID, createNotification())
                    initializeMediaProjection(resultCode, data)
                } else {
                    Log.e(TAG, "Invalid result code or data")
                    stopSelf()
                }
            }
        }
        // START_STICKY keeps service running for instant captures
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        cleanup()
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Enables 'Save this page' screenshot feature"
                setShowBadge(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("screen_capture_ready")
            .setContentText("say_'save_this_page'_to_capture")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun getScreenMetrics() {
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            screenDensity = displayMetrics.densityDpi
        }
        Log.d(TAG, "Screen: ${screenWidth}x${screenHeight} @ ${screenDensity}dpi")
    }

    private fun initializeMediaProjection(resultCode: Int, data: Intent) {
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    isProjectionReady.set(false)
                    cleanup()
                }
            }, handler)

            // Pre-create ImageReader for fast captures
            setupImageReader()
            isProjectionReady.set(true)
            Log.d(TAG, "MediaProjection initialized - ready for instant captures")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection: ${e.message}", e)
            isProjectionReady.set(false)
            stopSelf()
        }
    }

    private fun setupImageReader() {
        imageReader?.close()
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )
        Log.d(TAG, "ImageReader created")
    }

    private fun cleanup() {
        isProjectionReady.set(false)
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    /**
     * Capture screenshot - fast path, minimal overhead.
     */
    private suspend fun captureScreen(): String? = captureMutex.withLock {
        withContext(Dispatchers.IO) {
            val projection = mediaProjection
            val reader = imageReader

            if (projection == null || reader == null) {
                Log.e(TAG, "Projection or reader not ready")
                return@withContext null
            }

            try {
                val imageDeferred = CompletableDeferred<Image?>()

                // Set up one-shot listener
                reader.setOnImageAvailableListener({ imgReader ->
                    try {
                        val image = imgReader.acquireLatestImage()
                        if (!imageDeferred.isCompleted) {
                            imageDeferred.complete(image)
                        } else {
                            image?.close()
                        }
                    } catch (e: Exception) {
                        if (!imageDeferred.isCompleted) {
                            imageDeferred.complete(null)
                        }
                    }
                }, handler)

                // Create VirtualDisplay to trigger capture
                virtualDisplay?.release()
                virtualDisplay = projection.createVirtualDisplay(
                    "ScreenCapture",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )

                // Wait for image (short timeout for speed)
                val image = withTimeoutOrNull(1000L) {
                    imageDeferred.await()
                }

                // Release virtual display immediately after capture
                virtualDisplay?.release()
                virtualDisplay = null

                // Clear listener
                reader.setOnImageAvailableListener(null, null)

                if (image == null) {
                    Log.e(TAG, "Capture timeout or null image")
                    return@withContext null
                }

                // Convert and save
                val bitmap = imageToBitmap(image)
                image.close()

                if (bitmap == null) {
                    Log.e(TAG, "Failed to convert to bitmap")
                    return@withContext null
                }

                // Save to cache file
                val filename = "screenshot_${UUID.randomUUID()}.png"
                val file = File(cacheDir, filename)

                FileOutputStream(file).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 85, fos)
                }
                bitmap.recycle()

                Log.d(TAG, "Screenshot saved: ${file.absolutePath}")
                file.absolutePath

            } catch (e: Exception) {
                Log.e(TAG, "Capture error: ${e.message}", e)
                virtualDisplay?.release()
                virtualDisplay = null
                null
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmapWidth = screenWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(bitmapWidth, screenHeight, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to exact screen size if there's padding
            if (rowPadding > 0) {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                if (cropped != bitmap) bitmap.recycle()
                cropped
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap conversion error: ${e.message}", e)
            null
        }
    }
}
