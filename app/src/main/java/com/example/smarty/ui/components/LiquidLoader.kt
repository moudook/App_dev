package com.example.smarty.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.*
import kotlin.random.Random

/**
 * Premium Cosmic Particle Loader.
 * Uses a physics-based particle engine to render a realistic solar system.
 * Features:
 * - Parallax star dust (Depth simulation)
 * - Atmospheric refraction/glow for planets
 * - Particle-based orbital trails
 * - High-fidelity sun with interactive corona
 */
@Composable
fun LiquidLoader(
    modifier: Modifier = Modifier,
    audioAmplitude: Float = 0f
) {
    val isDark = isSystemInDarkTheme()

    // High-frequency time driver
    var time by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000.0
                time += dt
                last = now
            }
        }
    }

    // 1. Background Particle System (Star Dust with Depth)
    val backgroundParticles = remember {
        List(120) {
            CosmicParticle(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                depth = Random.nextFloat(), // 0 = far, 1 = near
                size = Random.nextFloat() * 1.5f + 0.5f,
                driftSpeed = Random.nextFloat() * 0.02f + 0.005f,
                twinkleSpeed = Random.nextFloat() * 3f + 1f
            )
        }
    }

    // 2. Solar Pulse Animation
    val pulseFactor by animateFloatAsState(
        targetValue = 1f + (audioAmplitude * 0.35f),
        animationSpec = spring(stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "SolarPulse"
    )

    // 3. Planet Trails History (For particle-like trailing)
    // We'll use a simple approximation for trails to keep performance smooth
    
    val sunColor = if (isDark) Color(0xFFFFE082) else Color(0xFFFFD54F)
    val starColor = if (isDark) Color.White else Color(0xFF455A64)

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height / 2)
        val viewRadius = size.minDimension * 0.48f

        // --- DRAW BACKGROUND PARTICLES ---
        backgroundParticles.forEach { p ->
            val drift = (time * p.driftSpeed).toFloat()
            var px = (p.x + drift * p.depth) % 1f
            var py = p.y
            
            val twinkle = (sin(time * p.twinkleSpeed + p.x * 100).toFloat() + 1f) / 2f
            val alpha = if (isDark) (0.1f + 0.4f * twinkle * p.depth) else (0.05f + 0.1f * twinkle)
            
            drawCircle(
                color = starColor.copy(alpha = alpha),
                radius = p.size * (0.5f + 0.5f * p.depth),
                center = Offset(px * size.width, py * size.height)
            )
        }

        // --- DRAW SUN (Layered Glow & Core) ---
        val sunRadius = viewRadius * 0.12f * pulseFactor
        
        // Far Glow
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to sunColor.copy(alpha = 0.2f),
                1.0f to Color.Transparent,
                center = center,
                radius = sunRadius * 4.5f
            ),
            radius = sunRadius * 4.5f,
            center = center
        )

        // Corona
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to sunColor.copy(alpha = 0.5f),
                0.6f to sunColor.copy(alpha = 0.1f),
                1.0f to Color.Transparent,
                center = center,
                radius = sunRadius * 1.8f
            ),
            radius = sunRadius * 1.8f,
            center = center
        )

        // Core
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to Color.White,
                0.3f to sunColor,
                1.0f to sunColor.copy(alpha = 0.8f),
                center = center,
                radius = sunRadius
            ),
            radius = sunRadius,
            center = center
        )

        // --- DRAW PLANETS ---
        val planets = listOf(
            PlanetInfo(0.20f, 0.025f, 5.0f, Color(0xFF9E9E9E)), // Mercury
            PlanetInfo(0.30f, 0.042f, 2.2f, Color(0xFFFFB74D)), // Venus
            PlanetInfo(0.42f, 0.048f, 1.4f, Color(0xFF4FC3F7)), // Earth
            PlanetInfo(0.55f, 0.035f, 0.8f, Color(0xFFFF5252)), // Mars
            PlanetInfo(0.70f, 0.105f, 0.4f, Color(0xFFFFE0B2)), // Jupiter
            PlanetInfo(0.85f, 0.090f, 0.25f, Color(0xFFFFF176)), // Saturn
            PlanetInfo(0.95f, 0.065f, 0.15f, Color(0xFF80DEEA))  // Uranus
        )

        planets.forEachIndexed { idx, p ->
            val orbitRadius = viewRadius * p.orbitFactor
            val angle = (time * p.speed * 0.15).toFloat()
            val px = center.x + cos(angle) * orbitRadius
            val py = center.y + sin(angle) * orbitRadius
            val pCenter = Offset(px, py)
            val pRadius = viewRadius * p.sizeFactor

            // 1. Draw Subtle Orbit Path
            drawCircle(
                color = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f),
                radius = orbitRadius,
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
            )

            // 2. Atmospheric Glow (Realistic Layer)
            drawCircle(
                brush = Brush.radialGradient(
                    0.0f to p.color.copy(alpha = 0.4f),
                    1.0f to Color.Transparent,
                    center = pCenter,
                    radius = pRadius * 2.2f
                ),
                radius = pRadius * 2.2f,
                center = pCenter
            )

            // 3. Particle Trail simulation (Smooth gradient arc)
            val trailLength = 35f * p.speed.coerceIn(0.5f, 3f)
            drawArc(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.9f to p.color.copy(alpha = 0.15f),
                    1f to Color.Transparent,
                    center = center
                ),
                startAngle = Math.toDegrees(angle.toDouble()).toFloat() - trailLength,
                sweepAngle = trailLength,
                useCenter = false,
                topLeft = Offset(center.x - orbitRadius, center.y - orbitRadius),
                size = Size(orbitRadius * 2, orbitRadius * 2),
                style = Stroke(width = pRadius * 0.6f, cap = StrokeCap.Round)
            )

            // 4. Planet Body with Refraction Lighting
            // Light source always from the sun (center)
            val lightAngle = atan2(py - center.y, px - center.x)
            val highlightOffset = Offset(
                px - cos(lightAngle) * pRadius * 0.4f,
                py - sin(lightAngle) * pRadius * 0.4f
            )

            val bodyBrush = Brush.radialGradient(
                0.0f to Color.White.copy(alpha = 0.8f), // Specular reflection
                0.2f to p.color,
                0.7f to p.color.copy(alpha = 0.9f),
                1.0f to Color.Black.copy(alpha = 0.4f), // Dark side
                center = highlightOffset,
                radius = pRadius * 1.5f
            )

            drawCircle(
                brush = bodyBrush,
                radius = pRadius,
                center = pCenter
            )

            // 5. Special Features: Saturn's Rings
            if (idx == 5) {
                withTransform({
                    rotate(degrees = 20f, pivot = pCenter)
                }) {
                    drawOval(
                        color = p.color.copy(alpha = 0.3f),
                        topLeft = Offset(px - pRadius * 2.2f, py - pRadius * 0.6f),
                        size = Size(pRadius * 4.4f, pRadius * 1.2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    // Fainter outer ring
                    drawOval(
                        color = p.color.copy(alpha = 0.15f),
                        topLeft = Offset(px - pRadius * 2.6f, py - pRadius * 0.8f),
                        size = Size(pRadius * 5.2f, pRadius * 1.6f),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            // 6. Special Features: Earth's Moon
            if (idx == 2) {
                val moonDist = pRadius * 2.8f
                val mAngle = (time * 12.0 * 0.15).toFloat()
                val mx = px + cos(mAngle) * moonDist
                val my = py + sin(mAngle) * moonDist
                
                // Moon Glow
                drawCircle(
                    color = Color.White.copy(alpha = 0.1f),
                    radius = pRadius * 0.8f,
                    center = Offset(mx, my)
                )
                // Moon Body
                drawCircle(
                    color = if (isDark) Color(0xFFCFD8DC) else Color(0xFF90A4AE),
                    radius = pRadius * 0.25f,
                    center = Offset(mx, my)
                )
            }
        }
    }
}

private data class CosmicParticle(
    val x: Float,
    val y: Float,
    val depth: Float,
    val size: Float,
    val driftSpeed: Float,
    val twinkleSpeed: Float
)

private data class PlanetInfo(
    val orbitFactor: Float,
    val sizeFactor: Float,
    val speed: Float,
    val color: Color
)


