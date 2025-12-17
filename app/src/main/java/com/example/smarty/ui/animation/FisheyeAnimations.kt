package com.example.smarty.ui.animation

import kotlin.math.abs
import kotlin.math.exp

/**
 * Fisheye Wave Animation System
 *
 * Provides mathematical functions for the fisheye lens wave effect animation
 * used in the alphabetical fast scroller. Uses Gaussian distribution for
 * smooth falloff where:
 * - The selected letter is largest (maxScale)
 * - Adjacent letters scale down following a Gaussian curve
 * - Letters far from selection return to normal size (scale = 1.0)
 */
object FisheyeAnimations {

    /**
     * Calculate the scale factor for a letter in the fisheye effect.
     *
     * Uses Gaussian distribution: scale(i) = 1 + (maxScale - 1) * e^(-(d^2) / (2 * sigma^2))
     *
     * @param letterIndex Index of the letter (0-25 for A-Z)
     * @param selectedIndex Currently selected letter index, -1 if none
     * @param maxScale Maximum magnification at center (default 2.0x)
     * @param sigma Wave spread factor - higher = wider wave (default 2.5)
     * @return Scale factor for the letter (1.0 to maxScale)
     */
    fun calculateFisheyeScale(
        letterIndex: Int,
        selectedIndex: Int,
        maxScale: Float = 2.8f,
        sigma: Float = 2.0f
    ): Float {
        if (selectedIndex < 0) return 1f

        val distance = abs(letterIndex - selectedIndex).toFloat()
        val gaussian = exp(-(distance * distance) / (2f * sigma * sigma))
        return 1f + (maxScale - 1f) * gaussian
    }

    /**
     * Calculate the horizontal offset for the wave bulge effect.
     *
     * Creates a visual "bump" where selected and nearby letters
     * extend outward from the scroller strip.
     *
     * @param letterIndex Index of the letter (0-25 for A-Z)
     * @param selectedIndex Currently selected letter index, -1 if none
     * @param maxOffset Maximum horizontal offset in dp (default 20f)
     * @param sigma Wave spread factor (default 2.5)
     * @return Horizontal offset in dp
     */
    fun calculateFisheyeOffset(
        letterIndex: Int,
        selectedIndex: Int,
        maxOffset: Float = 28f,
        sigma: Float = 2.0f
    ): Float {
        if (selectedIndex < 0) return 0f

        val distance = abs(letterIndex - selectedIndex).toFloat()
        val gaussian = exp(-(distance * distance) / (2f * sigma * sigma))
        return maxOffset * gaussian
    }

    /**
     * Calculate the alpha (opacity) for a letter in the fisheye effect.
     *
     * Dims letters far from selection while keeping selected letter
     * and nearby letters bright for focus.
     *
     * @param letterIndex Index of the letter (0-25 for A-Z)
     * @param selectedIndex Currently selected letter index, -1 if none
     * @param minAlpha Minimum alpha for far letters (default 0.4)
     * @param sigma Wave spread factor (default 3.0 - wider for alpha)
     * @return Alpha value (minAlpha to 1.0)
     */
    fun calculateFisheyeAlpha(
        letterIndex: Int,
        selectedIndex: Int,
        minAlpha: Float = 0.4f,
        sigma: Float = 3f
    ): Float {
        if (selectedIndex < 0) return 0.6f

        val distance = abs(letterIndex - selectedIndex).toFloat()
        val gaussian = exp(-(distance * distance) / (2f * sigma * sigma))
        return minAlpha + (1f - minAlpha) * gaussian
    }

    /**
     * Calculate the font weight emphasis for the selected letter.
     *
     * Returns a value that can be used to interpolate between
     * normal and bold font weights.
     *
     * @param letterIndex Index of the letter (0-25 for A-Z)
     * @param selectedIndex Currently selected letter index, -1 if none
     * @return Weight factor (0.0 to 1.0) where 1.0 = full emphasis
     */
    fun calculateFisheyeWeight(
        letterIndex: Int,
        selectedIndex: Int,
        sigma: Float = 1.5f
    ): Float {
        if (selectedIndex < 0) return 0f

        val distance = abs(letterIndex - selectedIndex).toFloat()
        val gaussian = exp(-(distance * distance) / (2f * sigma * sigma))
        return gaussian
    }
}
