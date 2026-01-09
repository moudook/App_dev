package com.example.smarty.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Modern Soft Shadow System
 * Soft, diffuse shadows to create a "floating" effect.
 * Not flat, but not skeuomorphic.
 *
 * Design spec shadows:
 * - Card: box-shadow: 0px 12px 24px -6px rgba(0,0,0,0.06), 0px 4px 8px -2px rgba(0,0,0,0.04)
 * - Active glow: box-shadow: 0px 4px 12px rgba(0,102,255,0.25)
 */
object JarvisShadow {
    // Card shadow - soft floating effect
    val cardElevation: Dp = 8.dp
    val cardSpotColor: Color = Color(0x0F000000)    // ~6% opacity black
    val cardAmbientColor: Color = Color(0x0A000000) // ~4% opacity black

    // Elevated card shadow - for modals/dialogs
    val elevatedElevation: Dp = 16.dp
    val elevatedSpotColor: Color = Color(0x14000000)    // ~8% opacity
    val elevatedAmbientColor: Color = Color(0x0A000000) // ~4% opacity

    // Active/Focus glow - Electric Blue
    val glowElevation: Dp = 12.dp
    val glowColor: Color = Color(0x400066FF) // 25% opacity Electric Blue

    // Subtle shadow for smaller elements
    val subtleElevation: Dp = 4.dp
    val subtleSpotColor: Color = Color(0x08000000) // ~3% opacity
}

/**
 * Soft card shadow modifier
 * Creates the diffuse "floating" effect from the design spec
 */
fun Modifier.softCardShadow(
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    elevation: Dp = JarvisShadow.cardElevation
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    spotColor = JarvisShadow.cardSpotColor,
    ambientColor = JarvisShadow.cardAmbientColor
)

/**
 * Elevated shadow for modals and dialogs
 */
fun Modifier.elevatedShadow(
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    elevation: Dp = JarvisShadow.elevatedElevation
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    spotColor = JarvisShadow.elevatedSpotColor,
    ambientColor = JarvisShadow.elevatedAmbientColor
)

/**
 * Active glow modifier for focused/active states
 * Creates Electric Blue glow effect
 */
fun Modifier.activeGlow(
    shape: RoundedCornerShape = RoundedCornerShape(12.dp),
    elevation: Dp = JarvisShadow.glowElevation
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    spotColor = JarvisShadow.glowColor,
    ambientColor = Color.Transparent
)

/**
 * Subtle shadow for smaller elements like chips
 */
fun Modifier.subtleShadow(
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    elevation: Dp = JarvisShadow.subtleElevation
): Modifier = this.shadow(
    elevation = elevation,
    shape = shape,
    spotColor = JarvisShadow.subtleSpotColor,
    ambientColor = Color.Transparent
)
