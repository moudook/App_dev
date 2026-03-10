package com.example.smarty.ui.utils

import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * Haptic Feedback Helper.
 * 
 * Single Responsibility: Only handles haptic feedback.
 * DRY: Centralizes haptic patterns used across the app.
 * 
 * Usage:
 * ```
 * // Get helper
 * val haptic = LocalHapticFeedback.current
 * 
 * // Or use helper methods
 * HapticHelper.click()
 * HapticHelper.longPress()
 * HapticHelper.textHandleMove()
 * ```
 */
object HapticHelper {
    
    /**
     * Perform click haptic feedback.
     */
    @Composable
    fun click() {
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    
    /**
     * Perform long press haptic feedback.
     */
    @Composable
    fun longPress() {
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    
    /**
     * Perform text handle move haptic feedback.
     */
    @Composable
    fun textHandleMove() {
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    
    /**
     * Perform keyboard tap haptic feedback.
     */
    @Composable
    fun keyboardTap() {
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(HapticFeedbackType.KeyboardTap)
    }
    
    /**
     * Perform virtual key haptic feedback.
     */
    @Composable
    fun virtualKey() {
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
    }
    
    /**
     * Perform gesture end haptic feedback.
     */
    @Composable
    fun gestureEnd() {
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
    }
    
    /**
     * Perform haptic feedback with confirmation.
     * Checks if haptic feedback is enabled on the device.
     */
    @Composable
    fun performWithConfirmation(
        type: HapticFeedbackType,
        enabled: Boolean = true
    ) {
        if (!enabled) return
        
        val haptic = LocalHapticFeedback.current
        haptic.performHapticFeedback(type)
    }
}

/**
 * Haptic feedback types for different interactions.
 */
enum class HapticInteraction {
    CLICK,
    LONG_PRESS,
    SCROLL,
    SWIPE,
    DRAG,
    DROP,
    SUCCESS,
    ERROR,
    WARNING
}

/**
 * Get haptic feedback type for interaction.
 */
fun HapticInteraction.toFeedbackType(): HapticFeedbackType {
    return when (this) {
        HapticInteraction.CLICK -> HapticFeedbackType.TextHandleMove
        HapticInteraction.LONG_PRESS -> HapticFeedbackType.LongPress
        HapticInteraction.SCROLL -> HapticFeedbackType.TextHandleMove
        HapticInteraction.SWIPE -> HapticFeedbackType.TextHandleMove
        HapticInteraction.DRAG -> HapticFeedbackType.TextHandleMove
        HapticInteraction.DROP -> HapticFeedbackType.GestureEnd
        HapticInteraction.SUCCESS -> HapticFeedbackType.TextHandleMove
        HapticInteraction.ERROR -> HapticFeedbackType.LongPress
        HapticInteraction.WARNING -> HapticFeedbackType.LongPress
    }
}
