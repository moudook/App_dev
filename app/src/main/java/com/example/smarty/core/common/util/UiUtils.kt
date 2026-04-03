package com.example.smarty.core.common.util

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized UI utility functions and Modifier extensions.
 *
 * These provide common UI patterns used across the Smarty codebase,
 * avoiding code duplication in individual composables.
 *
 * Guidelines:
 * - Only add utilities used in 2+ locations
 * - Keep Modifier extensions as `fun Modifier.extensionName()`
 * - Use `composed {}` for extensions that need `@Composable` context
 */

// 
// MODIFIER EXTENSIONS
// 

/**
 * Clickable modifier that suppresses the default ripple/indication.
 * Useful for custom touch targets where a ripple would be distracting.
 *
 * Usage:
 * Box(modifier = Modifier.noRippleClickable { doSomething() })
 */
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
 clickable(
 indication = null,
 interactionSource = remember { MutableInteractionSource() },
 onClick = onClick
 )
}

/**
 * Draws a left-side accent bar (vertical line) behind the composable.
 * Used for thinking sections, quote blocks, and hierarchical indicators.
 *
 * @param color The color of the accent bar
 * @param width The width of the bar (default 2.dp)
 * @param startPadding Left padding before the bar (default 8.dp)
 *
 * Usage:
 * Box(modifier = Modifier.leftAccentBar(accentColor))
 */
fun Modifier.leftAccentBar(
 color: Color,
 width: Dp = 2.dp,
 startPadding: Dp = 8.dp
): Modifier = composed {
 val density = LocalDensity.current
 this.drawBehind {
 val strokeWidthPx = with(density) { width.toPx() }
 val startPx = with(density) { startPadding.toPx() }
 drawLine(
 color = color,
 start = Offset(startPx, 0f),
 end = Offset(startPx, size.height),
 strokeWidth = strokeWidthPx
 )
 }
}

// 
// CONDITIONAL MODIFIERS
// 

/**
 * Conditionally applies a Modifier chain.
 * Avoids verbose `if/else Modifier` blocks in composable code.
 *
 * Usage:
 * Modifier
 * .conditionalModifier(isSelected) { background(selectedColor) }
 * .conditionalModifier(hasBorder) { border(1.dp, borderColor) }
 */
inline fun Modifier.conditionalModifier(
 condition: Boolean,
 modifier: Modifier.() -> Modifier
): Modifier {
 return if (condition) this.modifier() else this
}

// 
// PIXEL / DP CONVERSIONS
// 

/**
 * Converts Dp to pixels using the current density.
 * For use inside @Composable functions.
 */
@Composable
fun Dp.toPx(): Float {
 val density = LocalDensity.current
 return with(density) { this@toPx.toPx() }
}
