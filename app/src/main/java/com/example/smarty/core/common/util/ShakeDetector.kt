package com.example.smarty.core.common.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Detects phone shake gestures using the accelerometer sensor
 * Used to toggle between note input mode and AI chat mode
 *
 * @param context Android context
 * @param onShakeDetected Callback when shake is detected
 * @param getThreshold Optional function to get dynamic threshold (for sensitivity settings)
 */
class ShakeDetector(
    private val context: Context,
    private val onShakeDetected: () -> Unit,
    private val getThreshold: (() -> Int)? = null,
) : SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var lastShakeTime: Long = 0
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var lastZ: Float = 0f
    private var lastUpdate: Long = 0

    private var isStarted = false

    companion object {
        private const val TAG = "ShakeDetector"

        // Default shake detection threshold (used if getThreshold not provided)
        private const val DEFAULT_SHAKE_THRESHOLD = 800
        private const val SHAKE_COOLDOWN_MS = 250L // Minimum time between shake triggers (quick switching)
        private const val UPDATE_INTERVAL_MS = 100L // Minimum time between sensor reads

        // Haptic feedback duration
        private const val VIBRATE_DURATION_MS = 50L
    }

    /**
     * Get the current shake threshold.
     * Uses dynamic threshold from settings if available, otherwise default.
     */
    private fun getCurrentThreshold(): Int {
        return getThreshold?.invoke() ?: DEFAULT_SHAKE_THRESHOLD
    }

    /**
     * Start listening for shake gestures
     * Call this in onResume() of your Activity
     */
    fun start() {
        if (isStarted) return

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.let { sensor ->
            val registered =
                sensorManager?.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_UI,
                )
            isStarted = registered == true
            Log.d(TAG, "Shake detection started: $isStarted")
        } ?: run {
            Log.w(TAG, "Accelerometer sensor not available")
        }
    }

    /**
     * Stop listening for shake gestures
     * Call this in onPause() of your Activity to save battery
     */
    fun stop() {
        if (!isStarted) return

        sensorManager?.unregisterListener(this)
        isStarted = false
        Log.d(TAG, "Shake detection stopped")
    }

    /**
     * Check if shake detection is currently active
     */
    fun isActive(): Boolean = isStarted

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val currentTime = System.currentTimeMillis()

        // Throttle sensor processing
        if (currentTime - lastUpdate < UPDATE_INTERVAL_MS) return

        val timeDiff = currentTime - lastUpdate
        lastUpdate = currentTime

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate acceleration magnitude change
        val deltaX = x - lastX
        val deltaY = y - lastY
        val deltaZ = z - lastZ

        val speed =
            sqrt(
                (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ).toDouble(),
            ) / timeDiff * 10000

        // Check if shake threshold exceeded (using dynamic threshold from settings)
        if (speed > getCurrentThreshold()) {
            // Apply cooldown to prevent rapid triggers
            if (currentTime - lastShakeTime > SHAKE_COOLDOWN_MS) {
                lastShakeTime = currentTime
                Log.d(TAG, "Shake detected! Speed: $speed")

                // Notify listener - haptic feedback is handled by the callback
                // (so it only vibrates when shake is actually processed on correct screen)
                onShakeDetected()
            }
        }

        // Store current values for next comparison
        lastX = x
        lastY = y
        lastZ = z
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {
        // Not needed for shake detection
    }

    /**
     * Provide haptic feedback when shake is detected.
     * Call this from the callback only when the shake action is actually processed.
     * This ensures no haptic feedback on screens where shake is ignored.
     */
    fun triggerHapticFeedback() {
        try {
            val vibrator =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }

            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(
                        VibrationEffect.createOneShot(
                            VIBRATE_DURATION_MS,
                            VibrationEffect.DEFAULT_AMPLITUDE,
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(VIBRATE_DURATION_MS)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback failed: ${e.message}")
        }
    }

    /**
     * Manually trigger a shake event (for testing)
     */
    fun simulateShake() {
        onShakeDetected()
    }
}
