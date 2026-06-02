package com.example.smarty.core.common.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Playful haptic feedback system inspired by PS controller feedback.
 * Provides distinct, satisfying haptic patterns for different actions.
 */
object HapticFeedbackHelper {
    /**
     * Success feedback - Double tick pattern for successful actions
     * Like completing a task or successful save
     */
    fun successPulse(context: Context) {
        vibrate(context, longArrayOf(0, 20, 60, 20), intArrayOf(0, 180, 0, 120))
    }

    /**
     * Delete/Destructive - Sharp, crisp single feedback
     * Warning-like tactile response
     */
    fun deletePop(context: Context) {
        vibrate(context, longArrayOf(0, 30), intArrayOf(0, 255))
    }

    /**
     * Mode switch - Ascending wave pattern
     * Feels like transitioning between states
     */
    fun modeSwitchWave(context: Context) {
        vibrate(context, longArrayOf(0, 15, 30, 20, 30, 25), intArrayOf(0, 80, 0, 140, 0, 200))
    }

    /**
     * Archive action - Smooth slide-in feeling
     * Gentle, satisfying feedback for archiving
     */
    fun archiveSlide(context: Context) {
        vibrate(context, longArrayOf(0, 40, 20, 15), intArrayOf(0, 100, 0, 60))
    }

    /**
     * Selection toggle - Light tap for selecting/deselecting
     */
    fun selectionTap(context: Context) {
        vibrate(context, longArrayOf(0, 12), intArrayOf(0, 100))
    }

    /**
     * Swipe activation - Subtle threshold feedback
     * When swipe gesture passes threshold
     */
    fun swipeThreshold(context: Context) {
        vibrate(context, longArrayOf(0, 8), intArrayOf(0, 150))
    }

    /**
     * Refresh start - Soft pull-down feedback
     */
    fun refreshPull(context: Context) {
        vibrate(context, longArrayOf(0, 25), intArrayOf(0, 80))
    }

    /**
     * Long press activated - Confirm long press detection
     */
    fun longPressActivate(context: Context) {
        vibrate(context, longArrayOf(0, 35), intArrayOf(0, 200))
    }

    /**
     * Error/Invalid action - Quick double buzz
     */
    fun errorBuzz(context: Context) {
        vibrate(context, longArrayOf(0, 15, 40, 15), intArrayOf(0, 200, 0, 200))
    }

    /**
     * Undo available - Gentle reminder tap
     */
    fun undoReminder(context: Context) {
        vibrate(context, longArrayOf(0, 10, 80, 10), intArrayOf(0, 60, 0, 60))
    }

    private fun vibrate(
        context: Context,
        timings: LongArray,
        amplitudes: IntArray,
    ) {
        val vibrator = getVibrator(context) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator.vibrate(effect)
            } catch (e: Exception) {
                // Fallback to simple vibration
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings.sum())
            }
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings.sum())
        }
    }

    private fun getVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}

/**
 * Composable hook to get haptic helper with context
 */
@Composable
fun rememberHapticHelper(): HapticActions {
    val context = LocalContext.current
    val standardHaptic = LocalHapticFeedback.current

    return remember(context) {
        HapticActions(context, standardHaptic)
    }
}

/**
 * Convenience class bundling context and standard haptic feedback
 */
class HapticActions(
    private val context: Context,
    private val standard: HapticFeedback,
) {
    fun success() = HapticFeedbackHelper.successPulse(context)

    fun delete() = HapticFeedbackHelper.deletePop(context)

    fun modeSwitch() = HapticFeedbackHelper.modeSwitchWave(context)

    fun archive() = HapticFeedbackHelper.archiveSlide(context)

    fun select() = HapticFeedbackHelper.selectionTap(context)

    fun swipe() = HapticFeedbackHelper.swipeThreshold(context)

    fun refresh() = HapticFeedbackHelper.refreshPull(context)

    fun longPress() = HapticFeedbackHelper.longPressActivate(context)

    fun error() = HapticFeedbackHelper.errorBuzz(context)

    fun undo() = HapticFeedbackHelper.undoReminder(context)

    // Standard haptic for simple taps
    fun tap() = standard.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}
