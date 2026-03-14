package com.example.smarty.core.common.util

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Minimum touch target size for accessibility (WCAG 2.1 AA compliant).
 * 
 * All interactive elements MUST be at least 48dp x 48dp.
 */
val MinTouchTargetSize = 48.dp

/**
 * Creates an accessible clickable modifier with proper semantics.
 * 
 * This modifier ensures:
 * - Minimum 48dp touch target (WCAG compliant)
 * - Proper content description for screen readers
 * - Role assignment for accessibility services
 * - Visual indication on click
 * - Disabled state support
 *
 * USAGE:
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .accessibleClickable(
 *             onClick = { /* handle click */ },
 *             contentDescription = "Add note",
 *             enabled = true
 *         )
 * ) {
 *     Icon(Icons.Default.Add, contentDescription = null)
 * }
 * ```
 *
 * @param onClick Click handler
 * @param contentDescription Description for screen readers
 * @param enabled Whether the element is enabled
 * @param role Semantic role (Button, Checkbox, etc.)
 * @param shape Shape for indication (ripple)
 * @return Combined modifier with accessibility support
 */
@Composable
fun Modifier.accessibleClickable(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    role: Role = Role.Button,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    
    return this
        // Ensure minimum 48dp touch target
        .sizeIn(minWidth = MinTouchTargetSize, minHeight = MinTouchTargetSize)
        // Make clickable with indication
        .clip(shape)
        .clickable(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interactionSource,
            indication = LocalIndication.current
        )
        // Add semantics for accessibility
        .semantics {
            this.contentDescription = contentDescription
            this.role = role
            if (!enabled) {
                disabled()
            }
        }
}

/**
 * Creates an accessible clickable modifier for icon buttons.
 * 
 * Similar to accessibleClickable but optimized for icon-sized elements.
 * Ensures the touch target is 48dp even if the icon is smaller.
 *
 * USAGE:
 * ```kotlin
 * Icon(
 *     imageVector = Icons.Default.Add,
 *     contentDescription = "Add item",
 *     modifier = Modifier
 *         .iconButtonClickable(onClick = { /* handle click */ })
 * )
 * ```
 */
@Composable
fun Modifier.iconButtonClickable(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true
): Modifier {
    return this
        .sizeIn(minWidth = MinTouchTargetSize, minHeight = MinTouchTargetSize)
        .accessibleClickable(
            onClick = onClick,
            contentDescription = contentDescription,
            enabled = enabled,
            role = Role.Button
        )
}

/**
 * Checks if a color has sufficient contrast against a background.
 * 
 * WCAG 2.1 AA requires:
 * - 4.5:1 for normal text (< 18pt or < 14pt bold)
 * - 3:1 for large text (≥ 18pt or ≥ 14pt bold)
 *
 * USAGE:
 * ```kotlin
 * val hasContrast = Color.White.hasSufficientContrast(Color.Black, isLargeText = false)
 * if (hasContrast) {
 *     // Safe to use
 * } else {
 *     // Use different color
 * }
 * ```
 *
 * @param backgroundColor Background color to check against
 * @param isLargeText Whether this is for large text (lower contrast requirement)
 * @return true if contrast ratio meets WCAG 2.1 AA standards
 */
fun Color.hasSufficientContrast(
    backgroundColor: Color,
    isLargeText: Boolean = false
): Boolean {
    val luminance1 = this.luminance()
    val luminance2 = backgroundColor.luminance()
    
    val lighter = maxOf(luminance1, luminance2)
    val darker = minOf(luminance1, luminance2)
    
    val contrastRatio = (lighter + 0.05f) / (darker + 0.05f)
    
    return if (isLargeText) {
        contrastRatio >= 3.0f  // 3:1 for large text
    } else {
        contrastRatio >= 4.5f  // 4.5:1 for normal text
    }
}

/**
 * Calculate relative luminance per WCAG 2.1.
 * https://www.w3.org/WAI/GL/wiki/Relative_luminance
 */
private fun Color.luminance(): Float {
    fun Float.luminanceComponent(): Float {
        return if (this <= 0.03928f) {
            this / 12.92f
        } else {
            Math.pow(((this + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
    }

    return 0.2126f * red.luminanceComponent() +
           0.7152f * green.luminanceComponent() +
           0.0722f * blue.luminanceComponent()
}

/**
 * Get a color with sufficient contrast against a background.
 * 
 * If the color doesn't have sufficient contrast, returns a modified version
 * that does have sufficient contrast.
 *
 * USAGE:
 * ```kotlin
 * val accessibleColor = textColor.withContrastAgainst(backgroundColor)
 * ```
 */
fun Color.withContrastAgainst(
    backgroundColor: Color,
    isLargeText: Boolean = false
): Color {
    if (hasSufficientContrast(backgroundColor, isLargeText)) {
        return this
    }
    
    // Determine if we should lighten or darken
    val bgLuminance = backgroundColor.luminance()
    return if (bgLuminance > 0.5f) {
        // Dark background, use light color
        Color.White
    } else {
        // Light background, use dark color
        Color.Black
    }
}
