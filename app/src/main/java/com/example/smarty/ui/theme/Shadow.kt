package com.example.smarty.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

import androidx.compose.ui.composed

/**
 * Modern Soft Shadow System
 * Soft, diffuse shadows to create a "floating" effect.
 * Not flat, but not skeuomorphic.
 *
 * Design spec shadows:
 * - Card: box-shadow: 0px 12px 24px -6px rgba(0,0,0,0.06), 0px 4px 8px -2px rgba(0,0,0,0.04)
 * - Active glow: box-shadow: 0px 4px 12px rgba(0,102,255,0.25)
 */
object SmartyShadow {
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
 * Generalized to support custom colors and shapes.
 */
fun Modifier.softCardShadow(
    elevation: Dp = SmartyShadow.cardElevation,
    shape: Shape? = null,
    spotColor: Color = SmartyShadow.cardSpotColor,
    ambientColor: Color = SmartyShadow.cardAmbientColor
): Modifier = composed {
    this.then(
        Modifier.shadow(
            elevation = elevation,
            shape = shape ?: LocalShapes.current.card,
            spotColor = spotColor,
            ambientColor = ambientColor
        )
    )
}

/**
 * Elevated shadow for modals and dialogs
 */
fun Modifier.elevatedShadow(
    elevation: Dp = SmartyShadow.elevatedElevation,
    shape: Shape? = null,
    spotColor: Color = SmartyShadow.elevatedSpotColor,
    ambientColor: Color = SmartyShadow.elevatedAmbientColor
): Modifier = composed {
    this.then(
        Modifier.shadow(
            elevation = elevation,
            shape = shape ?: LocalShapes.current.dialog,
            spotColor = spotColor,
            ambientColor = ambientColor
        )
    )
}

/**
 * Active glow modifier for focused/active states
 * Creates Electric Blue glow effect
 */
fun Modifier.activeGlow(
    elevation: Dp = SmartyShadow.glowElevation,
    shape: Shape? = null,
    spotColor: Color = SmartyShadow.glowColor
): Modifier = composed {
    this.then(
        Modifier.shadow(
            elevation = elevation,
            shape = shape ?: LocalShapes.current.button,
            spotColor = spotColor,
            ambientColor = Color.Transparent
        )
    )
}

/**
 * Subtle shadow for smaller elements like chips
 */
fun Modifier.subtleShadow(
    elevation: Dp = SmartyShadow.subtleElevation,
    shape: Shape? = null,
    spotColor: Color = SmartyShadow.subtleSpotColor
): Modifier = composed {
    this.then(
        Modifier.shadow(
            elevation = elevation,
            shape = shape ?: LocalShapes.current.tag,
            spotColor = spotColor,
            ambientColor = Color.Transparent
        )
    )
}

