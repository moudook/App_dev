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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

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
                .offset(y = 80.dp) // Push text below center graphic
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
@Composable
fun ChatEmptyState(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current
    
    EmptyStateContainer(
        title = "Focus Mode",
        subtitle = "Aligning your thoughts.",
        hint = "I help bring clarity to your notes and ideas.",
        modifier = modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "cognitive_alignment")
        
        // Master rotation driver
        val t by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
            label = "t"
        )
        
        // "Focus" Pulse - Triggers when rings align (every 180 degrees approx)
        val focusPulse by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing =  EaseInOutSine), // Half the rotation time for 2 alignments
                repeatMode = RepeatMode.Reverse
            ),
            label = "focusPulse"
        )

        Canvas(modifier = Modifier.size(140.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val maxRadius = size.width / 2.2f
            
            // Central "Lens" Core
            drawCircle(
                color = accentColor,
                radius = 6.dp.toPx(),
                center = Offset(cx, cy)
            )
            // Outer focus ring (Static reference)
            drawCircle(
                color = accentColor.copy(alpha = 0.1f),
                radius = maxRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
            )

            // Ring 1: Inner (Fast, 3 segments)
            val r1 = maxRadius * 0.4f
            withTransform({ rotate(t * 2f, pivot = Offset(cx, cy)) }) {
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = accentColor.copy(alpha = 0.6f),
                    startAngle = 0f, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(cx - r1, cy - r1), size = Size(r1 * 2, r1 * 2), style = stroke
                )
                drawArc(
                    color = accentColor.copy(alpha = 0.6f),
                    startAngle = 120f, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(cx - r1, cy - r1), size = Size(r1 * 2, r1 * 2), style = stroke
                )
                drawArc(
                    color = accentColor.copy(alpha = 0.6f),
                    startAngle = 240f, sweepAngle = 80f, useCenter = false,
                    topLeft = Offset(cx - r1, cy - r1), size = Size(r1 * 2, r1 * 2), style = stroke
                )
            }

            // Ring 2: Middle (Medium, 2 segments)
            val r2 = maxRadius * 0.7f
            // Rotates opposite direction for contrast
            withTransform({ rotate(-t * 1.5f, pivot = Offset(cx, cy)) }) {
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = accentColor.copy(alpha = 0.4f),
                    startAngle = 0f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(cx - r2, cy - r2), size = Size(r2 * 2, r2 * 2), style = stroke
                )
                drawArc(
                    color = accentColor.copy(alpha = 0.4f),
                    startAngle = 180f, sweepAngle = 140f, useCenter = false,
                    topLeft = Offset(cx - r2, cy - r2), size = Size(r2 * 2, r2 * 2), style = stroke
                )
            }
            
            // Ring 3: Outer (Slow, 1 segment/Scanner)
            val r3 = maxRadius * 0.95f
            val isAligned = (t % 360) < 10 || (t % 360) > 350 // Visual alignment window
            val outerAlpha = if (isAligned) 1f else 0.3f
            
            withTransform({ 
                rotate(t, pivot = Offset(cx, cy)) 
                scale(focusPulse, focusPulse, pivot = Offset(cx, cy))
            }) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = accentColor.copy(alpha = outerAlpha),
                    startAngle = -20f, sweepAngle = 40f, useCenter = false,
                    topLeft = Offset(cx - r3, cy - r3), size = Size(r3 * 2, r3 * 2), style = stroke
                )
                
                // Alignment Markers (Top/Bottom/Left/Right)
                for(i in 0..3) {
                     drawCircle(
                        color = accentColor.copy(alpha = 0.2f),
                        radius = 2.dp.toPx(),
                        center = Offset(cx + cos(i * PI/2).toFloat() * r3, cy + sin(i * PI/2).toFloat() * r3)
                     )
                }
            }
        }
    }
}

/**
 * Notes Empty State: "Spark of Idea"
 * Represents the creation of a new thought.
 */
@Composable
fun NotesEmptyState(modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current
    
    EmptyStateContainer(
        title = "Hello Himmu",
        subtitle = "Everything starts with an idea.",
        hint = "Improving it is as important, as the idea itself",
        modifier = modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "idea_spark")
        val scale by infiniteTransition.animateFloat(
            initialValue = 0.8f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "scale"
        )
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(20000, easing = LinearEasing)),
            label = "rotation"
        )

        Canvas(modifier = Modifier.size(140.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            
            // Rotating geometric petals
            withTransform({
                rotate(rotation, center)
                scale(scale, scale, center)
            }) {
                for (i in 0 until 4) {
                    rotate(i * 45f + rotation) {
                        drawRoundRect(
                            color = accentColor.copy(alpha = 0.1f),
                            topLeft = Offset(center.x - 10.dp.toPx(), center.y - 40.dp.toPx()),
                            size = Size(20.dp.toPx(), 80.dp.toPx()),
                            cornerRadius = CornerRadius(10.dp.toPx())
                        )
                    }
                }
            }
            
            // Central Core
            drawCircle(
                color = accentColor.copy(alpha = 0.2f),
                radius = 15.dp.toPx(),
                center = center
            )
            drawCircle(
                color = accentColor,
                radius = 6.dp.toPx(),
                center = center
            )
        }
    }
}

/**
 * Archive Empty State: "Clean Slate"
 * Represents stored/archived items in a clean stack.
 * Features a parallax levitation effect where layers float with independent rhythm,
 * creating a deep 3D sensation.
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
        val infiniteTransition = rememberInfiniteTransition(label = "archive_layers")
        
        // Master floater
        val t by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 2f * PI.toFloat(),
            animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
            label = "t"
        )

        Canvas(modifier = Modifier.size(140.dp)) {
            val cx = size.width / 2
            val cy = size.height / 2
            val cardWidth = 50.dp.toPx()
            val cardHeight = 70.dp.toPx() // Portrait aspect ratio like screenshot
            val cornerRadius = CornerRadius(12.dp.toPx())
            
            // Draw 4 layers for depth (Screenshot shows deeply stacked look)
            val layers = 4
            
            for (i in 0 until layers) {
                // Reverse index (0 is bottom/furthest, 3 is top/closest)
                val reverseI = layers - 1 - i
                
                // Parallax Logic:
                // Lower layers move slower and with less amplitude
                val amplitude = 5.dp.toPx() * (0.5f + (i * 0.15f))
                val phase = i * 0.5f // Offset phase
                val yFloat = sin(t + phase) * amplitude
                
                // Scale/Perspective
                val scale = 0.8f + (i * 0.05f) // Top is largest
                val alpha = 0.3f + (i * 0.2f)  // Top is brightest
                
                // Y Position: Stacked upwards visually
                // We offset them vertically so they "peek" out from behind
                val stackOffset = (reverseI * 12.dp.toPx())
                
                // Draw Card
                // Use withTransform to handle scale from center
                withTransform({
                    translate(left = cx, top = cy - stackOffset + yFloat)
                    scale(scale, scale)
                    translate(left = -cx, top = -cy) // Pivot at center
                }) {
                    drawRoundRect(
                        color = accentColor.copy(alpha = alpha),
                        topLeft = Offset(cx - cardWidth/2, cy - cardHeight/2),
                        size = Size(cardWidth, cardHeight),
                        cornerRadius = cornerRadius
                    )
                    
                    // Optional: Add a subtle border to the top card for definition
                    if (i == layers - 1) {
                         drawRoundRect(
                            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f),
                            topLeft = Offset(cx - cardWidth/2, cy - cardHeight/2),
                            size = Size(cardWidth, cardHeight),
                            cornerRadius = cornerRadius,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }
                }
            }
        }
    }
}

/**
 * Stacks Empty State: "Organized Grid"
 * Represents structure and categorization.
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
        val infiniteTransition = rememberInfiniteTransition(label = "grid_pulse")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse"
        )

        Canvas(modifier = Modifier.size(120.dp)) {
            val gap = 8.dp.toPx()
            val boxSize = 30.dp.toPx()
            val totalSize = (boxSize * 2) + gap
            val startX = (size.width - totalSize) / 2
            val startY = (size.height - totalSize) / 2
            
            // 2x2 Grid
            // Top Left
            drawRoundRect(
                color = accentColor.copy(alpha = pulse),
                topLeft = Offset(startX, startY),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            // Top Right
            drawRoundRect(
                color = accentColor.copy(alpha = 1.3f - pulse), // Inverse pulse
                topLeft = Offset(startX + boxSize + gap, startY),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            // Bottom Left
            drawRoundRect(
                color = accentColor.copy(alpha = 1.3f - pulse), // Inverse pulse
                topLeft = Offset(startX, startY + boxSize + gap),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            // Bottom Right
            drawRoundRect(
                color = accentColor.copy(alpha = pulse),
                topLeft = Offset(startX + boxSize + gap, startY + boxSize + gap),
                size = Size(boxSize, boxSize),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
        }
    }
}

@Composable
fun CategoryEmptyState(categoryName: String, modifier: Modifier = Modifier) {
    val accentColor = LocalAccentColor.current
    
    EmptyStateContainer(
        title = categoryName,
        subtitle = "Waiting for your brilliance.",
        hint = "Add notes to populate this stack.",
        modifier = modifier
    ) {
         val infiniteTransition = rememberInfiniteTransition(label = "folder_hover")
         val hover by infiniteTransition.animateFloat(
             initialValue = -5f,
             targetValue = 5f,
             animationSpec = infiniteRepeatable(tween(2500, easing = EaseInOutSine), RepeatMode.Reverse),
             label = "hover"
         )

         Canvas(modifier = Modifier.size(100.dp)) {
             val cx = size.width / 2
             val cy = size.height / 2
             val folderSize = 60.dp.toPx()
             
             withTransform({ translate(0f, hover) }) {
                 // Folder Body
                 drawRoundRect(
                     color = accentColor.copy(alpha = 0.15f),
                     topLeft = Offset(cx - folderSize/2, cy - folderSize/3),
                     size = Size(folderSize, folderSize * 0.7f),
                     cornerRadius = CornerRadius(8.dp.toPx())
                 )
                 
                 // Folder Tab
                 drawRoundRect(
                     color = accentColor.copy(alpha = 0.25f),
                     topLeft = Offset(cx - folderSize/2, cy - folderSize/3 - 15),
                     size = Size(folderSize * 0.4f, 20f),
                     cornerRadius = CornerRadius(4.dp.toPx())
                 )
                 
                 // "Empty" content line inside
                 drawLine(
                     color = accentColor.copy(alpha = 0.3f),
                     start = Offset(cx - folderSize/3, cy),
                     end = Offset(cx + folderSize/3, cy),
                     strokeWidth = 3.dp.toPx(),
                     cap = StrokeCap.Round
                 )
             }
         }
    }
}

