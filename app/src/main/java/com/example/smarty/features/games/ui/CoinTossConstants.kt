package com.example.smarty.features.games.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Centralized constants for the CoinToss feature.
 * All magic numbers, colors, URLs, and timing values live here
 * so the UI and animation files stay clean and easily tweakable.
 */
object CoinTossConstants {

    // ── Coin Dimensions ──────────────────────────────────────────
    val COIN_SIZE = 220.dp
    const val EDGE_SLICE_COUNT = 12

    // ── Coin Image URLs ──────────────────────────────────────────
    const val HEADS_IMAGE_URL =
        "https://upload.wikimedia.org/wikipedia/commons/d/df/George_Washington_Presidential_%241_Coin_obverse.jpg"
    const val TAILS_IMAGE_URL =
        "https://upload.wikimedia.org/wikipedia/commons/2/23/Statue_of_Liberty_Presidential_%241_Coin_reverse.jpg"

    // ── Color Palette ────────────────────────────────────────────
    val COIN_FALLBACK_COLOR = Color(0xFFB8860B)
    val COIN_SPOT_SHADOW = Color(0xFF8B6508)

    val EDGE_GRADIENT_COLORS = listOf(
        Color(0xFF8B6508),
        Color(0xFFDAA520),
        Color(0xFF5A4005),
        Color(0xFFDAA520),
        Color(0xFF8B6508)
    )

    val ROYAL_GOLD_GRADIENT = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFFF0A8), // Bright highlight
            Color(0xFFFFD700), // Gold
            Color(0xFFB8860B), // Dark Gold
            Color(0xFFDAA520), // Mid Gold
            Color(0xFFFFF0A8)  // Highlight edge
        ),
        start = Offset(0f, 0f),
        end = Offset(200f, 1000f)
    )

    // ── Shadow Defaults ──────────────────────────────────────────
    val FLOOR_SHADOW_WIDTH = 160.dp
    val FLOOR_SHADOW_HEIGHT = 24.dp
    val FLOOR_SHADOW_OFFSET_Y = 140.dp

    // ── Animation Timing (ms) ────────────────────────────────────
    const val LAUNCH_TIME = 600
    const val PEAK_TIME = 3800    // Slow-mo "wish" period
    const val FALL_TIME = 600
    const val MIN_SPINS = 12

    // ── Trajectory ───────────────────────────────────────────────
    const val LAUNCH_HEIGHT = -600f
    const val APEX_DRIFT = -650f
    const val BOUNCE_HEIGHT = -40f
    const val BOUNCE_UP_MS = 150
    const val BOUNCE_DOWN_MS = 150

    // ── Camera & Zoom ────────────────────────────────────────────
    const val DEFAULT_CAMERA_DIST = 16f
    const val CLOSE_CAMERA_DIST = 8f
    const val DEFAULT_ZOOM = 1.0f
    const val LAUNCH_ZOOM = 2.2f
    const val PEAK_ZOOM = 2.4f

    // ── Tilt ─────────────────────────────────────────────────────
    const val LAUNCH_TILT_X = 50f
    const val PEAK_TILT_X = 70f
}
