package com.example.smarty.ui.components.common

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.AnimationDuration
import com.example.smarty.ui.utils.*

/**
 * =============================================================================
 * COGNI UNIFIED EMPTY STATE COMPONENT
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
    data class FolderHover(val categoryName: String) : EmptyStateAnimation()
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
fun CogniEmptyState(
    title: String,
    subtitle: String = "",
    hint: String? = null,
    animationType: EmptyStateAnimation = EmptyStateAnimation.CloudBreath,
    modifier: Modifier = Modifier
) {
    EmptyStateContainer(
        title = title,
        subtitle = subtitle,
        hint = hint,
        modifier = modifier
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
    graphic: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    val isKeyboardVisible = WindowInsets.ime.getBottom(density) > 0

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Graphic Layer
        graphic()

        // Text Layer
        AnimatedVisibility(
            visible = !isKeyboardVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .offset(y = 150.dp)
                    .padding(horizontal = 32.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LocalAccentColor.current
                )

                if (subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                }

                if (hint != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = Alpha.heavy),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 280.dp)
                    )
                }
            }
        }
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
    val floatY: Float
) {
    companion object {
        /** Default static state when animation is paused */
        val DEFAULT = CloudBreathWaveState(
            auraScale = 2.2f,
            auraAlpha = Alpha.moderate,
            cloudScale = 1.5f,
            cloudAlpha = 0.4f,
            coreScale = 0.8f,
            coreAlpha = Alpha.mostlyOpaque,
            floatY = 0f
        )
    }
}

@Composable
private fun CloudBreathAnimation() {
    val accentColor = LocalAccentColor.current
    val shouldAnimate = shouldAnimationRun()

    val infiniteTransition = if (shouldAnimate) {
        rememberInfiniteTransition(label = "cloud_breath")
    } else null

    val breathPhase by if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = TWO_PI_F,
            animationSpec = infiniteRepeatable(
                animation = tween(AnimationDuration.slowCycle, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "breath"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val waveState by remember {
        derivedStateOf {
            if (!shouldAnimate) {
                CloudBreathWaveState.DEFAULT
            } else {
                val auraWave = fastSin(breathPhase)
                val cloudWave = fastSin(breathPhase + PI_F * 0.25f)
                val coreWave = fastSin(breathPhase)
                val beat = (coreWave + 1f) * 0.5f
                val floatY = 5f * fastSin(breathPhase * 0.5f)
                CloudBreathWaveState(
                    auraScale = 2.2f + auraWave * 0.1f,
                    auraAlpha = (Alpha.moderate + auraWave * 0.05f).coerceIn(0f, 1f),
                    cloudScale = 1.5f + cloudWave * 0.15f,
                    cloudAlpha = (0.4f + cloudWave * 0.1f).coerceIn(0f, 1f),
                    coreScale = 0.8f + beat * 0.2f,
                    coreAlpha = (Alpha.mostlyOpaque + beat * 0.2f).coerceIn(0f, 1f),
                    floatY = floatY
                )
            }
        }
    }

    val density = LocalDensity.current
    val baseSizePx = remember(density) { with(density) { 50.dp.toPx() } }
    val maxAuraRadius = baseSizePx * 2.4f

    val auraBrush = remember(accentColor, maxAuraRadius) {
        Brush.radialGradient(
            colors = listOf(accentColor.copy(alpha = Alpha.half), Color.Transparent),
            radius = maxAuraRadius
        )
    }

    val cloudBrush = remember(accentColor, baseSizePx) {
        Brush.radialGradient(
            colors = listOf(accentColor, accentColor.copy(alpha = Alpha.moderate), Color.Transparent),
            radius = baseSizePx * 1.8f
        )
    }

    val coreBrush = remember(accentColor, baseSizePx) {
        Brush.radialGradient(
            colors = listOf(accentColor, accentColor.copy(alpha = Alpha.half), Color.Transparent),
            radius = baseSizePx * 1.1f
        )
    }

    Canvas(modifier = Modifier.size(160.dp)) {
        val center = this.center
        val state = waveState

        // 1. AURA LAYER - Outermost glow
        drawCircle(
            brush = auraBrush,
            radius = baseSizePx * state.auraScale,
            center = center,
            alpha = state.auraAlpha
        )

        // 2. CLOUD LAYER - Middle ethereal layer
        drawCircle(
            brush = cloudBrush,
            radius = baseSizePx * state.cloudScale,
            center = center,
            alpha = state.cloudAlpha
        )

        // 3. CORE LAYER - Inner soul with subtle float
        drawCircle(
            brush = coreBrush,
            radius = baseSizePx * state.coreScale,
            center = Offset(center.x, center.y + state.floatY),
            alpha = state.coreAlpha
        )
    }
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
    val layers: List<CardLayer>
)

private data class CardLayer(
    val amplitude: Float,
    val phase: Float,
    val scale: Float,
    val color: Color,
    val stackOffset: Float,
    val isTopLayer: Boolean
)

@Composable
private fun LayeredCardsAnimation() {
    val accentColor = LocalAccentColor.current
    val shouldAnimate = shouldAnimationRun()

    val infiniteTransition = if (shouldAnimate) {
        rememberInfiniteTransition(label = "archive_layers")
    } else null

    val t by if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = TWO_PI_F,
            animationSpec = infiniteRepeatable(
                animation = tween(AnimationDuration.verySlow, easing = LinearEasing)
            ),
            label = "t"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val density = LocalDensity.current
    val layerConfig = remember(density, accentColor) {
        with(density) {
            val cardWidth = 50.dp.toPx()
            val cardHeight = 70.dp.toPx()
            val baseAmplitude = 5.dp.toPx()
            val stackStep = 12.dp.toPx()
            val cornerRadiusPx = 12.dp.toPx()
            val strokeWidth = 1.dp.toPx()

            LayeredCardsConfig(
                cardWidth = cardWidth,
                cardHeight = cardHeight,
                cornerRadius = CornerRadius(cornerRadiusPx),
                cardSize = Size(cardWidth, cardHeight),
                strokeWidth = strokeWidth,
                borderColor = Color.White.copy(alpha = Alpha.emphasis),
                layers = (0 until 4).map { i ->
                    val reverseI = 3 - i
                    CardLayer(
                        amplitude = baseAmplitude * (0.5f + i * 0.15f),
                        phase = i * 0.5f,
                        scale = 0.8f + i * 0.05f,
                        color = accentColor.copy(alpha = Alpha.emphasis + i * Alpha.moderate),
                        stackOffset = reverseI * stackStep,
                        isTopLayer = (i == 3)
                    )
                }
            )
        }
    }

    val yFloats by remember {
        derivedStateOf {
            if (!shouldAnimate) {
                listOf(0f, 0f, 0f, 0f)
            } else {
                layerConfig.layers.map { layer ->
                    fastSin(t + layer.phase) * layer.amplitude
                }
            }
        }
    }

    Canvas(modifier = Modifier.size(140.dp)) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val halfWidth = layerConfig.cardWidth * 0.5f
        val halfHeight = layerConfig.cardHeight * 0.5f

        layerConfig.layers.forEachIndexed { i, layer ->
            val yFloat = yFloats[i]

            withTransform({
                translate(left = cx, top = cy - layer.stackOffset + yFloat)
                scale(layer.scale, layer.scale)
                translate(left = -cx, top = -cy)
            }) {
                val topLeft = Offset(cx - halfWidth, cy - halfHeight)

                drawRoundRect(
                    color = layer.color,
                    topLeft = topLeft,
                    size = layerConfig.cardSize,
                    cornerRadius = layerConfig.cornerRadius
                )

                if (layer.isTopLayer) {
                    drawRoundRect(
                        color = layerConfig.borderColor,
                        topLeft = topLeft,
                        size = layerConfig.cardSize,
                        cornerRadius = layerConfig.cornerRadius,
                        style = Stroke(width = layerConfig.strokeWidth)
                    )
                }
            }
        }
    }
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
    val cornerRadius: CornerRadius
)

@Composable
private fun GridPulseAnimation() {
    val accentColor = LocalAccentColor.current
    val shouldAnimate = shouldAnimationRun()

    val infiniteTransition = if (shouldAnimate) {
        rememberInfiniteTransition(label = "grid_pulse")
    } else null

    val pulse by if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = Alpha.emphasis,
            targetValue = Alpha.opaque,
            animationSpec = infiniteRepeatable(
                animation = tween(AnimationDuration.breathCycle, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
    } else {
        remember { mutableStateOf(0.65f) }
    }

    val alphas by remember {
        derivedStateOf {
            Pair(pulse, (1.3f - pulse).coerceIn(0f, 1f))
        }
    }

    val density = LocalDensity.current
    val gridConfig = remember(density) {
        with(density) {
            val gap = 8.dp.toPx()
            val boxSize = 30.dp.toPx()
            val totalSize = boxSize * 2 + gap
            val cornerRadius = CornerRadius(6.dp.toPx())
            val boxSizeObj = Size(boxSize, boxSize)

            GridPulseConfig(
                boxSize = boxSize,
                boxSizeObj = boxSizeObj,
                gap = gap,
                totalSize = totalSize,
                cornerRadius = cornerRadius
            )
        }
    }

    val primaryColor = remember(accentColor, alphas.first) {
        accentColor.copy(alpha = alphas.first)
    }
    val inverseColor = remember(accentColor, alphas.second) {
        accentColor.copy(alpha = alphas.second)
    }

    Canvas(modifier = Modifier.size(120.dp)) {
        val startX = (size.width - gridConfig.totalSize) * 0.5f
        val startY = (size.height - gridConfig.totalSize) * 0.5f
        val offsetRight = gridConfig.boxSize + gridConfig.gap
        val offsetDown = gridConfig.boxSize + gridConfig.gap

        // 2x2 Grid - Checkerboard pulse pattern
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(startX, startY),
            size = gridConfig.boxSizeObj,
            cornerRadius = gridConfig.cornerRadius
        )
        drawRoundRect(
            color = inverseColor,
            topLeft = Offset(startX + offsetRight, startY),
            size = gridConfig.boxSizeObj,
            cornerRadius = gridConfig.cornerRadius
        )
        drawRoundRect(
            color = inverseColor,
            topLeft = Offset(startX, startY + offsetDown),
            size = gridConfig.boxSizeObj,
            cornerRadius = gridConfig.cornerRadius
        )
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(startX + offsetRight, startY + offsetDown),
            size = gridConfig.boxSizeObj,
            cornerRadius = gridConfig.cornerRadius
        )
    }
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
    val lineColor: Color
)

@Composable
private fun FolderHoverAnimation() {
    val accentColor = LocalAccentColor.current
    val shouldAnimate = shouldAnimationRun()

    val infiniteTransition = if (shouldAnimate) {
        rememberInfiniteTransition(label = "folder_hover")
    } else null

    val hover by if (infiniteTransition != null) {
        infiniteTransition.animateFloat(
            initialValue = -5f,
            targetValue = 5f,
            animationSpec = infiniteRepeatable(
                animation = tween(AnimationDuration.gentleCycle, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "hover"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    val density = LocalDensity.current
    val folderConfig = remember(density, accentColor) {
        with(density) {
            val folderSize = 60.dp.toPx()
            val halfFolder = folderSize * 0.5f
            val thirdFolder = folderSize / 3f

            FolderHoverConfig(
                folderSize = folderSize,
                halfFolder = halfFolder,
                thirdFolder = thirdFolder,
                bodyCorner = CornerRadius(8.dp.toPx()),
                tabCorner = CornerRadius(4.dp.toPx()),
                bodySize = Size(folderSize, folderSize * 0.7f),
                tabSize = Size(folderSize * 0.4f, 20f),
                lineWidth = 3.dp.toPx(),
                bodyColor = accentColor.copy(alpha = Alpha.medium),
                tabColor = accentColor.copy(alpha = Alpha.strong),
                lineColor = accentColor.copy(alpha = Alpha.emphasis)
            )
        }
    }

    Canvas(modifier = Modifier.size(100.dp)) {
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val cfg = folderConfig

        withTransform({ translate(0f, hover) }) {
            // Folder Body
            drawRoundRect(
                color = cfg.bodyColor,
                topLeft = Offset(cx - cfg.halfFolder, cy - cfg.thirdFolder),
                size = cfg.bodySize,
                cornerRadius = cfg.bodyCorner
            )

            // Folder Tab
            drawRoundRect(
                color = cfg.tabColor,
                topLeft = Offset(cx - cfg.halfFolder, cy - cfg.thirdFolder - 15f),
                size = cfg.tabSize,
                cornerRadius = cfg.tabCorner
            )

            // "Empty" content line inside
            drawLine(
                color = cfg.lineColor,
                start = Offset(cx - cfg.thirdFolder, cy),
                end = Offset(cx + cfg.thirdFolder, cy),
                strokeWidth = cfg.lineWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
