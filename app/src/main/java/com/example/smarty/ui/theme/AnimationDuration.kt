package com.example.smarty.ui.theme

/**
 * Centralized animation duration constants in milliseconds.
 * Replaces scattered hardcoded animation timings throughout the codebase.
 */
object AnimationDuration {
    // Micro interactions (50-100ms)
    const val instant = 50
    const val micro = 80
    const val quick = 100

    // Standard transitions (150-200ms)
    const val fast = 150
    const val standard = 200
    const val normal = 240

    // Deliberate animations (300-400ms)
    const val medium = 300
    const val emphasized = 400

    // Long running (600ms+)
    const val slow = 600
    const val breath = 640 // Pulsing/breathing
    const val shimmer = 800 // Loading shimmer

    // Infinite animation cycles
    const val shimmerCycle = 1400
    const val breathCycle = 1500
    const val gentleCycle = 2500
    const val slowCycle = 4000
    const val verySlow = 6000
}
