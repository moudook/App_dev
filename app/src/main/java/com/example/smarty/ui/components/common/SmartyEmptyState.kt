package com.example.smarty.ui.components.common

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.utils.*

/**
 * =============================================================================
 * Smarty UNIFIED EMPTY STATE COMPONENT
 * =============================================================================
 *
 * Consolidates the 5 duplicated empty states into a single reusable component:
 * - ChatEmptyState -> CloudBreath animation
 * - NotesEmptyState -> CloudBreath animation
 * - ArchiveEmptyState -> LayeredCards animation
 * - StacksEmptyState -> GridPulse animation
 * - CategoryEmptyState -> FolderHover animation
 *
 * Features:
 * - Lifecycle-aware animation pause/resume
 * - Pre-computed brushes and geometry for performance
 * - Bhaskara I sine approximation for faster calculations
 * - Weber-Fechner perceptual optimization
 * - Zero-allocation draw loops
 *
 * =============================================================================
 */

/**
 * Animation types for empty states.
 * Each represents a distinct visual style for different contexts.
 */
sealed class EmptyStateAnimation {
    /** Breathing cloud effect - used for Chat and Notes empty states */
    object CloudBreath : EmptyStateAnimation()

    /** Floating layered cards - used for Archive empty state */
    object LayeredCards : EmptyStateAnimation()

    /** Pulsing grid pattern - used for Stacks empty state */
    object GridPulse : EmptyStateAnimation()

    /** Hovering folder icon - used for Category empty state */
    data class FolderHover(
        val categoryName: String,
    ) : EmptyStateAnimation()
}

/**
 * Unified empty state component that displays an animated graphic with text.
 *
 * @param title Main title text displayed below the animation
 * @param subtitle Secondary descriptive text
 * @param hint Optional hint text shown in smaller font
 * @param animationType The type of animation to display
 * @param modifier Modifier for the container
 */
@Composable
fun SmartyEmptyState(
    title: String,
    subtitle: String = "",
    hint: String? = null,
    animationType: EmptyStateAnimation = EmptyStateAnimation.CloudBreath,
    modifier: Modifier = Modifier,
) {
    EmptyStateContainer(
        title = title,
        subtitle = subtitle,
        hint = hint,
        modifier = modifier,
    ) {
        when (animationType) {
            is EmptyStateAnimation.CloudBreath -> CloudBreathAnimation()
            is EmptyStateAnimation.LayeredCards -> LayeredCardsAnimation()
            is EmptyStateAnimation.GridPulse -> GridPulseAnimation()
            is EmptyStateAnimation.FolderHover -> FolderHoverAnimation()
        }
    }
}

// =============================================================================
// SHARED CONTAINER
// =============================================================================

/**
 * Shared container for text content in empty states to maintain consistency.
 */
@Composable
private fun EmptyStateContainer(
    title: String,
    subtitle: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
    graphic: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // Graphic Layer
        graphic()
        // Text Layer Removed as per user request
    }
}

// =============================================================================
// CLOUD BREATH ANIMATION (Chat, Notes)
// =============================================================================

/** Pre-computed wave state for CloudBreath animation */
private data class CloudBreathWaveState(
    val auraScale: Float,
    val auraAlpha: Float,
    val cloudScale: Float,
    val cloudAlpha: Float,
    val coreScale: Float,
    val coreAlpha: Float,
    val floatY: Float,
) {
    companion object {
        /** Default static state when animation is paused */
        val DEFAULT =
            CloudBreathWaveState(
                auraScale = 2.2f,
                auraAlpha = Alpha.moderate,
                cloudScale = 1.5f,
                cloudAlpha = 0.4f,
                coreScale = 0.8f,
                coreAlpha = Alpha.mostlyOpaque,
                floatY = 0f,
            )
    }
}

@Composable
private fun CloudBreathAnimation() {
    // No animation
}

// =============================================================================
// LAYERED CARDS ANIMATION (Archive)
// =============================================================================

/** Pre-computed configuration for LayeredCards animation */
private data class LayeredCardsConfig(
    val cardWidth: Float,
    val cardHeight: Float,
    val cornerRadius: CornerRadius,
    val cardSize: Size,
    val strokeWidth: Float,
    val borderColor: Color,
    val layers: List<CardLayer>,
)

private data class CardLayer(
    val amplitude: Float,
    val phase: Float,
    val scale: Float,
    val color: Color,
    val stackOffset: Float,
    val isTopLayer: Boolean,
)

@Composable
private fun LayeredCardsAnimation() {
    // No animation
}

// =============================================================================
// GRID PULSE ANIMATION (Stacks)
// =============================================================================

/** Pre-computed grid configuration for GridPulse animation */
private data class GridPulseConfig(
    val boxSize: Float,
    val boxSizeObj: Size,
    val gap: Float,
    val totalSize: Float,
    val cornerRadius: CornerRadius,
)

@Composable
private fun GridPulseAnimation() {
    // No animation
}

// =============================================================================
// FOLDER HOVER ANIMATION (Category)
// =============================================================================

/** Pre-computed folder configuration for FolderHover animation */
private data class FolderHoverConfig(
    val folderSize: Float,
    val halfFolder: Float,
    val thirdFolder: Float,
    val bodyCorner: CornerRadius,
    val tabCorner: CornerRadius,
    val bodySize: Size,
    val tabSize: Size,
    val lineWidth: Float,
    val bodyColor: Color,
    val tabColor: Color,
    val lineColor: Color,
)

@Composable
private fun FolderHoverAnimation() {
    // No animation
}
