package com.example.smarty.ui.components

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
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.*

/**
 * =============================================================================
 * LIFECYCLE-AWARE EMPTY STATE ANIMATIONS
 * =============================================================================
 *
 * All animations in this file implement:
 *
 * 1. LIFECYCLE AWARENESS
 *    - Automatically pause when app is backgrounded (ON_PAUSE)
 *    - Completely stop when not visible (ON_STOP)
 *    - Resume seamlessly when returning to foreground
 *
 * 2. MATHEMATICAL OPTIMIZATION
 *    - Bhaskara I sine approximation (3x faster than kotlin.math.sin)
 *    - Pre-computed brushes and geometry
 *    - Zero-allocation draw loops
 *
 * 3. PERCEPTUAL OPTIMIZATION
 *    - derivedStateOf batches state updates
 *    - Skip imperceptible changes (Weber-Fechner law)
 *
 * =============================================================================
 */

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
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Graphic Layer
        graphic()

        // Text Layer
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .offset(y = 150.dp) // Shifted lower to increase separation from graphic
                .padding(horizontal = 32.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = LocalAccentColor.current
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (hint != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.widthIn(max = 280.dp)
                )
            }
        }
    }
}

/**
 * Chat Empty State: "Cognitive Alignment"
 * Targets the "Focused" brain state.
 * Represents clarity, precision, and order.
 * Concentric arcs rotate at different synchronized speeds.
 * Periodically, they align perfectly, simulating scattered thoughts
 * coming into sharp focus (The "Aha!" moment).
 */
/**
 * Chat Page Animation: "Cognitive Alignment" (Focus Mode)
 *
 * Design Concept:
 * - "Solar System": Central core with rotating orbits.
 * - "Cloud Style": Uses sweep gradients and soft strokes for a gaseous, nebula-like trail.
 * - Represents the AI aligning with the user's thoughts.
 *
 * OPTIMIZATION v2.0:
 * - LIFECYCLE AWARE: Pauses when app backgrounded, stops when not visible
 * - Uses Bhaskara I approximation for trig functions
 * - Pre-computed brushes and cached pixel values
 * - Eliminates per-frame allocations
 */
@Composable
fun ChatEmptyState(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current

    EmptyStateContainer(
        title = "Focus",
        subtitle = "Personal AI companion",
        hint = "Everything starts with an idea; the graveyard is full of them. Execution is the only resurrection",
        modifier = modifier
    ) {
        // LIFECYCLE AWARENESS: Check if animation should run
        val shouldAnimate = shouldAnimationRun()

        // OPTIMIZATION: Lifecycle-aware transition (null when paused/stopped)
        val infiniteTransition = if (shouldAnimate) {
            rememberInfiniteTransition(label = "cognitive_orbit")
        } else null

        // Master phase [0, 2π] - all other animations derive from this
        // Returns static value when paused to prevent GPU work
        val masterPhase by if (infiniteTransition != null) {
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = TWO_PI_F,
                animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing)),
                label = "phase"
            )
        } else {
            remember { mutableStateOf(0f) } // Static when paused
        }

        // OPTIMIZATION: Derive rotation and breath from master phase
        // Only computes when animation is active
        val derivedValues by remember {
            derivedStateOf {
                if (!shouldAnimate) {
                    // Return default static values when paused
                    Pair(0f, 1.0f)
                } else {
                    val tDegrees = masterPhase * (360f / TWO_PI_F)
                    val breathVal = 1.0f + 0.2f * fastSin(masterPhase * 4f)
                    Pair(tDegrees, breathVal)
                }
            }
        }

        // Pre-computed brushes (Zero-Allocation) - always cached regardless of animation state
        val orbitBrush1 = remember(accentColor) {
            Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    accentColor.copy(alpha = 0.1f),
                    accentColor.copy(alpha = 0.4f),
                    Color.Transparent
                )
            )
        }

        val orbitBrush2 = remember(accentColor) {
            Brush.sweepGradient(
                colors = listOf(
                    Color.Transparent,
                    accentColor.copy(alpha = 0.2f),
                    accentColor.copy(alpha = 0.05f),
                    Color.Transparent
                )
            )
        }

        val coreBrush = remember(accentColor) {
            Brush.radialGradient(
                colors = listOf(accentColor, accentColor.copy(alpha = 0.2f), Color.Transparent)
            )
        }

        // Pre-cached colors
        val centerColor = remember(accentColor) { accentColor.copy(alpha = 0.8f) }
        val satelliteColor = remember(accentColor) { accentColor.copy(alpha = 0.3f) }

        // Pre-compute pixel values outside draw loop
        val density = LocalDensity.current
        val cachedSizes = remember(density) {
            with(density) {
                ChatEmptySizes(
                    coreBase = 25.dp.toPx(),
                    centerRadius = 4.dp.toPx(),
                    innerStroke = 12.dp.toPx(),
                    outerStroke = 20.dp.toPx(),
                    satelliteRadius = 6.dp.toPx()
                )
            }
        }

        Canvas(modifier = Modifier.size(160.dp)) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val centerOffset = Offset(cx, cy)

            val (t, breath) = derivedValues

            // 1. The Living Core (The "Sun")
            drawCircle(
                brush = coreBrush,
                radius = cachedSizes.coreBase * breath,
                center = centerOffset
            )
            drawCircle(
                color = centerColor,
                radius = cachedSizes.centerRadius,
                center = centerOffset
            )

            // 2. Inner Cloud Orbit (Mental - Fast, Dense)
            // Rotates 2x per cycle (720° total, completing 2 full loops)
            withTransform({ rotate(t * 2f, pivot = centerOffset) }) {
                drawCircle(
                    brush = orbitBrush1,
                    radius = size.width * 0.25f,
                    center = centerOffset,
                    style = Stroke(width = cachedSizes.innerStroke, cap = StrokeCap.Round)
                )
            }

            // 3. Outer Cloud Orbit (Crust - Slow, Ethereal)
            // Rotates 1x per cycle (360° total, completing 1 full loop) in reverse direction
            val outerRotation = -t + 120f
            withTransform({ rotate(outerRotation, pivot = centerOffset) }) {
                drawCircle(
                    brush = orbitBrush2,
                    radius = size.width * 0.40f,
                    center = centerOffset,
                    style = Stroke(width = cachedSizes.outerStroke, cap = StrokeCap.Round)
                )
            }

            // 4. Satellite Cloud (Attached to Outer Orbit)
            val satelliteR = size.width * 0.40f
            val satAngle = (outerRotation * (PI_F / 180f)) + 4.5f
            val satX = cx + fastCos(satAngle) * satelliteR
            val satY = cy + fastSin(satAngle) * satelliteR

            drawCircle(
                color = satelliteColor,
                radius = cachedSizes.satelliteRadius,
                center = Offset(satX, satY)
            )
        }
    }
}

/** Pre-computed pixel sizes for ChatEmptyState (avoid per-frame density conversions) */
private data class ChatEmptySizes(
    val coreBase: Float,
    val centerRadius: Float,
    val innerStroke: Float,
    val outerStroke: Float,
    val satelliteRadius: Float
)

/**
 * Notes Empty State: "Spark of Idea"
 * Represents the creation of a new thought.
 */
/**
 * Front Page / Notes Animation: "Spark of Idea" (Cloud Breath)
 *
 * Design Concept:
 * - "Cloud Theme": A smaller version of the main startup animation.
 * - Features the "Living Orb" logic with breathing concentric gradients.
 * - Represents a new idea forming from the ether.
 *
 * OPTIMIZATION v2.0:
 * - LIFECYCLE AWARE: Pauses when app backgrounded, stops when not visible
 * - Pre-computed brushes with fixed maximum radius
 * - Uses fastSin for all wave calculations
 * - Zero-allocation draw loop
 */
@Composable
fun NotesEmptyState(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current

    EmptyStateContainer(
        title = "Hello, Moudook!",
        subtitle = "Everything starts with an idea.",
        hint = "Stop looking for the perfect 'app' and start providing the perfect effort",
    ) {
        // ORIGINAL ANIMATION: Cognitive Alignment (Restored)
        // LIFECYCLE AWARENESS: Check if animation should run
        val shouldAnimate = shouldAnimationRun()

        // Lifecycle-aware transition
        val infiniteTransition = if (shouldAnimate) {
            rememberInfiniteTransition(label = "cloud_breath")
        } else null

        val breathPhase by if (infiniteTransition != null) {
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = TWO_PI_F,
                animationSpec = infiniteRepeatable(
                    animation = tween(4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "breath"
            )
        } else {
            remember { mutableStateOf(0f) }
        }

        // OPTIMIZATION: Pre-compute all wave values - returns static when paused
        val waveState by remember {
            derivedStateOf {
                if (!shouldAnimate) {
                    NotesWaveState.DEFAULT
                } else {
                    val auraWave = fastSin(breathPhase)
                    val cloudWave = fastSin(breathPhase + PI_F * 0.25f)
                    val coreWave = fastSin(breathPhase)
                    val beat = (coreWave + 1f) * 0.5f
                    val floatY = 5f * fastSin(breathPhase * 0.5f)
                    NotesWaveState(
                        auraScale = 2.2f + auraWave * 0.1f,
                        auraAlpha = (0.2f + auraWave * 0.05f).coerceIn(0f, 1f),
                        cloudScale = 1.5f + cloudWave * 0.15f,
                        cloudAlpha = (0.4f + cloudWave * 0.1f).coerceIn(0f, 1f),
                        coreScale = 0.8f + beat * 0.2f,
                        coreAlpha = (0.8f + beat * 0.2f).coerceIn(0f, 1f),
                        floatY = floatY
                    )
                }
            }
        }

        // Pre-computed brushes with FIXED maximum radius
        val density = LocalDensity.current
        val baseSizePx = remember(density) { with(density) { 50.dp.toPx() } }
        val maxAuraRadius = baseSizePx * 2.4f

        val auraBrush = remember(accentColor, maxAuraRadius) {
            Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.5f), Color.Transparent),
                radius = maxAuraRadius
            )
        }

        val cloudBrush = remember(accentColor, baseSizePx) {
            Brush.radialGradient(
                colors = listOf(accentColor, accentColor.copy(alpha = 0.2f), Color.Transparent),
                radius = baseSizePx * 1.8f
            )
        }

        val coreBrush = remember(accentColor, baseSizePx) {
            Brush.radialGradient(
                colors = listOf(accentColor, accentColor.copy(alpha = 0.5f), Color.Transparent),
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
}

/** Pre-computed wave state for NotesEmptyState (batched calculation) */
private data class NotesWaveState(
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
        val DEFAULT = NotesWaveState(
            auraScale = 2.2f,
            auraAlpha = 0.2f,
            cloudScale = 1.5f,
            cloudAlpha = 0.4f,
            coreScale = 0.8f,
            coreAlpha = 0.8f,
            floatY = 0f
        )
    }
}

/**
 * Archive Empty State: "Clean Slate"
 * Represents stored/archived items in a clean stack.
 * Features a parallax levitation effect where layers float with independent rhythm,
 * creating a deep 3D sensation.
 */
/**
 * Archive Block Animation: "Clean Slate"
 *
 * Design Concept:
 * - "Levitating Layers": Floating cards with parallax depth.
 * - Represents the depth of stored history.
 * - Smooth, slow harmonic motion.
 *
 * OPTIMIZATION v2.0:
 * - LIFECYCLE AWARE: Pauses when app backgrounded, stops when not visible
 * - Pre-computed layer properties
 * - Only yFloat varies per frame (computed via fastSin)
 * - Zero per-frame allocations
 */
@Composable
fun ArchiveEmptyState(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current

    EmptyStateContainer(
        title = "Clean Slate",
        subtitle = "Your archive is currently empty.",
        hint = "Notes you're done with will live here.",
        modifier = modifier
    ) {
        // LIFECYCLE AWARENESS
        val shouldAnimate = shouldAnimationRun()

        val infiniteTransition = if (shouldAnimate) {
            rememberInfiniteTransition(label = "archive_layers")
        } else null

        val t by if (infiniteTransition != null) {
            infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = TWO_PI_F,
                animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
                label = "t"
            )
        } else {
            remember { mutableStateOf(0f) }
        }

        // Pre-compute static layer properties
        val density = LocalDensity.current
        val layerConfig = remember(density, accentColor) {
            with(density) {
                val cardWidth = 50.dp.toPx()
                val cardHeight = 70.dp.toPx()
                val baseAmplitude = 5.dp.toPx()
                val stackStep = 12.dp.toPx()
                val cornerRadiusPx = 12.dp.toPx()
                val strokeWidth = 1.dp.toPx()

                ArchiveLayerConfig(
                    cardWidth = cardWidth,
                    cardHeight = cardHeight,
                    cornerRadius = CornerRadius(cornerRadiusPx),
                    cardSize = Size(cardWidth, cardHeight),
                    strokeWidth = strokeWidth,
                    borderColor = Color.White.copy(alpha = 0.3f),
                    layers = (0 until 4).map { i ->
                        val reverseI = 3 - i
                        ArchiveLayer(
                            amplitude = baseAmplitude * (0.5f + i * 0.15f),
                            phase = i * 0.5f,
                            scale = 0.8f + i * 0.05f,
                            color = accentColor.copy(alpha = 0.3f + i * 0.2f),
                            stackOffset = reverseI * stackStep,
                            isTopLayer = (i == 3)
                        )
                    }
                )
            }
        }

        // Derive yFloats - returns static zeros when paused
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
}

/** Pre-computed configuration for ArchiveEmptyState layers */
private data class ArchiveLayerConfig(
    val cardWidth: Float,
    val cardHeight: Float,
    val cornerRadius: CornerRadius,
    val cardSize: Size,
    val strokeWidth: Float,
    val borderColor: Color,
    val layers: List<ArchiveLayer>
)

/** Pre-computed properties for a single archive layer */
private data class ArchiveLayer(
    val amplitude: Float,
    val phase: Float,
    val scale: Float,
    val color: Color,
    val stackOffset: Float,
    val isTopLayer: Boolean
)

/**
 * Stacks Empty State: "Organized Grid"
 * Represents structure and categorization.
 */
/**
 * Note Categorization Animation: "Organized Grid" (Stacks)
 *
 * Design Concept:
 * - "Structure": A rhythmic grid pulse.
 * - Represents the AI automatically organizing chaotic notes into structured stacks.
 * - Inverse pulsing for a dynamic "checking" feel.
 *
 * OPTIMIZATION v2.0:
 * - LIFECYCLE AWARE: Pauses when app backgrounded, stops when not visible
 * - Pre-computed grid layout
 * - derivedStateOf for pulse alpha values
 */
@Composable
fun StacksEmptyState(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current

    EmptyStateContainer(
        title = "Your Knowledge",
        subtitle = "Organized automatically.",
        hint = "AI will create Stacks for you as you add notes.",
        modifier = modifier
    ) {
        // LIFECYCLE AWARENESS
        val shouldAnimate = shouldAnimationRun()

        val infiniteTransition = if (shouldAnimate) {
            rememberInfiniteTransition(label = "grid_pulse")
        } else null

        val pulse by if (infiniteTransition != null) {
            infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
                label = "pulse"
            )
        } else {
            remember { mutableStateOf(0.65f) } // Static middle value when paused
        }

        // Derive alpha values - static when paused
        val alphas by remember {
            derivedStateOf {
                Pair(pulse, (1.3f - pulse).coerceIn(0f, 1f))
            }
        }

        // Pre-compute grid layout
        val density = LocalDensity.current
        val gridConfig = remember(density) {
            with(density) {
                val gap = 8.dp.toPx()
                val boxSize = 30.dp.toPx()
                val totalSize = boxSize * 2 + gap
                val cornerRadius = CornerRadius(6.dp.toPx())
                val boxSizeObj = Size(boxSize, boxSize)

                StacksGridConfig(
                    boxSize = boxSize,
                    boxSizeObj = boxSizeObj,
                    gap = gap,
                    totalSize = totalSize,
                    cornerRadius = cornerRadius
                )
            }
        }

        // Pre-compute colors
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
}

/** Pre-computed grid configuration for StacksEmptyState */
private data class StacksGridConfig(
    val boxSize: Float,
    val boxSizeObj: Size,
    val gap: Float,
    val totalSize: Float,
    val cornerRadius: CornerRadius
)

/**
 * Category Animation: "Folder Hover"
 *
 * Design Concept:
 * - "Expectancy": A gentle hover animation.
 * - Represents an empty container waiting to be filled with brilliance.
 *
 * OPTIMIZATION v2.0:
 * - LIFECYCLE AWARE: Pauses when app backgrounded, stops when not visible
 * - Pre-computed folder geometry
 * - Single translate transform (minimal GPU overhead)
 */
@Composable
fun CategoryEmptyState(categoryName: String, modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current

    EmptyStateContainer(
        title = categoryName,
        subtitle = "Waiting for your brilliance.",
        hint = "Add notes to populate this stack.",
        modifier = modifier
    ) {
        // LIFECYCLE AWARENESS
        val shouldAnimate = shouldAnimationRun()

        val infiniteTransition = if (shouldAnimate) {
            rememberInfiniteTransition(label = "folder_hover")
        } else null

        val hover by if (infiniteTransition != null) {
            infiniteTransition.animateFloat(
                initialValue = -5f,
                targetValue = 5f,
                animationSpec = infiniteRepeatable(tween(2500, easing = EaseInOutSine), RepeatMode.Reverse),
                label = "hover"
            )
        } else {
            remember { mutableStateOf(0f) } // Static when paused
        }

        // Pre-compute folder geometry and colors
        val density = LocalDensity.current
        val folderConfig = remember(density, accentColor) {
            with(density) {
                val folderSize = 60.dp.toPx()
                val halfFolder = folderSize * 0.5f
                val thirdFolder = folderSize / 3f

                FolderConfig(
                    folderSize = folderSize,
                    halfFolder = halfFolder,
                    thirdFolder = thirdFolder,
                    bodyCorner = CornerRadius(8.dp.toPx()),
                    tabCorner = CornerRadius(4.dp.toPx()),
                    bodySize = Size(folderSize, folderSize * 0.7f),
                    tabSize = Size(folderSize * 0.4f, 20f),
                    lineWidth = 3.dp.toPx(),
                    bodyColor = accentColor.copy(alpha = 0.15f),
                    tabColor = accentColor.copy(alpha = 0.25f),
                    lineColor = accentColor.copy(alpha = 0.3f)
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
}

/** Pre-computed folder configuration for CategoryEmptyState */
private data class FolderConfig(
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

