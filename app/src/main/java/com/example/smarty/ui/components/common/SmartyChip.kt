package com.example.smarty.ui.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.AnimationDuration
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.LocalSpacing

/**
 * =============================================================================
 * Smarty CHIP COMPONENT
 * =============================================================================
 *
 * A reusable chip component that consolidates chip-like UI elements across
 * the application. Supports multiple variants, sizes, and interaction states.
 *
 * Features:
 * - Multiple visual variants (Default, Accent, Muted, Category)
 * - Three size options (Small, Medium, Large)
 * - Optional leading and trailing icons
 * - Selection state with animated color transitions
 * - Consistent styling using design system tokens
 *
 * =============================================================================
 */

/**
 * Visual variants for SmartyChip.
 */
enum class ChipVariant {
    /** Standard chip with subtle background */
    Default,

    /** Accent-colored chip for primary actions or selections */
    Accent,

    /** Muted chip with minimal visual prominence */
    Muted,

    /** Category-style chip for filter/tag purposes */
    Category
}

/**
 * Size options for SmartyChip.
 */
enum class ChipSize {
    /** Compact chip for dense layouts */
    Small,

    /** Standard chip size */
    Medium,

    /** Larger chip for prominent actions */
    Large
}

/**
 * Size configuration for chip dimensions.
 */
private data class ChipSizeConfig(
    val height: Dp,
    val horizontalPadding: Dp,
    val iconSize: Dp,
    val iconSpacing: Dp
)

/**
 * Color configuration for chip appearance.
 */
private data class ChipColorConfig(
    val backgroundColor: Color,
    val selectedBackgroundColor: Color,
    val contentColor: Color,
    val selectedContentColor: Color,
    val borderColor: Color?,
    val selectedBorderColor: Color?
)

/**
 * A reusable chip component with multiple variants and sizes.
 *
 * @param label The text to display in the chip
 * @param onClick Optional click handler. If null, chip is not clickable.
 * @param leadingIcon Optional icon displayed before the label
 * @param trailingIcon Optional icon displayed after the label
 * @param onTrailingClick Optional separate click handler for trailing icon
 * @param isSelected Whether the chip is in a selected state
 * @param variant Visual style variant
 * @param size Size of the chip
 * @param modifier Modifier for the chip container
 */
@Composable
fun SmartyChip(
    label: String,
    onClick: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    isSelected: Boolean = false,
    variant: ChipVariant = ChipVariant.Default,
    size: ChipSize = ChipSize.Medium,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    val spacing = LocalSpacing.current
    val shapes = LocalShapes.current

    // Get size configuration
    val sizeConfig = remember(size) {
        when (size) {
            ChipSize.Small -> ChipSizeConfig(
                height = 28.dp,
                horizontalPadding = 10.dp,
                iconSize = 14.dp,
                iconSpacing = 4.dp
            )
            ChipSize.Medium -> ChipSizeConfig(
                height = 36.dp,
                horizontalPadding = 14.dp,
                iconSize = 16.dp,
                iconSpacing = 6.dp
            )
            ChipSize.Large -> ChipSizeConfig(
                height = 44.dp,
                horizontalPadding = 18.dp,
                iconSize = 20.dp,
                iconSpacing = 8.dp
            )
        }
    }

    // Get color configuration based on variant
    // Note: Colors from MaterialTheme.colorScheme must be accessed at composition time
    val colorScheme = MaterialTheme.colorScheme
    val colorConfig = when (variant) {
        ChipVariant.Default -> ChipColorConfig(
            backgroundColor = colorScheme.surfaceVariant,
            selectedBackgroundColor = accentColor.copy(alpha = Alpha.medium),
            contentColor = colorScheme.onSurfaceVariant,
            selectedContentColor = accentColor,
            borderColor = null,
            selectedBorderColor = accentColor.copy(alpha = Alpha.emphasis)
        )
        ChipVariant.Accent -> ChipColorConfig(
            backgroundColor = accentColor.copy(alpha = Alpha.light),
            selectedBackgroundColor = accentColor.copy(alpha = Alpha.strong),
            contentColor = accentColor,
            selectedContentColor = accentColor,
            borderColor = accentColor.copy(alpha = Alpha.moderate),
            selectedBorderColor = accentColor
        )
        ChipVariant.Muted -> ChipColorConfig(
            backgroundColor = colorScheme.surface,
            selectedBackgroundColor = colorScheme.surfaceVariant,
            contentColor = colorScheme.onSurface.copy(alpha = Alpha.prominent),
            selectedContentColor = colorScheme.onSurface,
            borderColor = colorScheme.outline.copy(alpha = Alpha.hint),
            selectedBorderColor = colorScheme.outline.copy(alpha = Alpha.moderate)
        )
        ChipVariant.Category -> ChipColorConfig(
            backgroundColor = accentColor.copy(alpha = Alpha.subtle),
            selectedBackgroundColor = accentColor.copy(alpha = Alpha.moderate),
            contentColor = accentColor.copy(alpha = Alpha.mostlyOpaque),
            selectedContentColor = accentColor,
            borderColor = accentColor.copy(alpha = Alpha.soft),
            selectedBorderColor = accentColor.copy(alpha = Alpha.half)
        )
    }

    // Animate colors on selection change
    val animatedBackgroundColor by animateColorAsState(
        targetValue = if (isSelected) colorConfig.selectedBackgroundColor else colorConfig.backgroundColor,
        animationSpec = tween(AnimationDuration.fast),
        label = "chip_bg"
    )

    val animatedContentColor by animateColorAsState(
        targetValue = if (isSelected) colorConfig.selectedContentColor else colorConfig.contentColor,
        animationSpec = tween(AnimationDuration.fast),
        label = "chip_content"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = (if (isSelected) colorConfig.selectedBorderColor else colorConfig.borderColor)
            ?: Color.Transparent,
        animationSpec = tween(AnimationDuration.fast),
        label = "chip_border"
    )

    // Determine the shape based on variant
    val chipShape = when (variant) {
        ChipVariant.Default, ChipVariant.Accent -> shapes.pill
        ChipVariant.Muted -> shapes.tag
        ChipVariant.Category -> shapes.button
    }

    // Base modifier with background and border
    val baseModifier = modifier
        .height(sizeConfig.height)
        .clip(chipShape)
        .background(animatedBackgroundColor)
        .then(
            if (colorConfig.borderColor != null || colorConfig.selectedBorderColor != null) {
                Modifier.border(
                    width = 1.dp,
                    color = animatedBorderColor,
                    shape = chipShape
                )
            } else {
                Modifier
            }
        )

    // Add click behavior if onClick is provided
    val clickableModifier = if (onClick != null) {
        baseModifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(),
            role = Role.Button,
            onClick = onClick
        )
    } else {
        baseModifier
    }

    Row(
        modifier = clickableModifier
            .padding(horizontal = sizeConfig.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Leading icon
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(sizeConfig.iconSize),
                tint = animatedContentColor
            )
            Spacer(modifier = Modifier.width(sizeConfig.iconSpacing))
        }

        // Label
        Text(
            text = label,
            style = when (size) {
                ChipSize.Small -> MaterialTheme.typography.labelSmall
                ChipSize.Medium -> MaterialTheme.typography.labelMedium
                ChipSize.Large -> MaterialTheme.typography.labelLarge
            },
            color = animatedContentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        // Trailing icon
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(sizeConfig.iconSpacing))

            val trailingModifier = if (onTrailingClick != null) {
                Modifier
                    .size(sizeConfig.iconSize + 8.dp)
                    .clip(shapes.avatar)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(radius = sizeConfig.iconSize),
                        role = Role.Button,
                        onClick = onTrailingClick
                    )
                    .padding(4.dp)
            } else {
                Modifier.size(sizeConfig.iconSize)
            }

            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                modifier = trailingModifier,
                tint = animatedContentColor
            )
        }
    }
}

/**
 * Convenience composable for a filter chip with toggle behavior.
 *
 * @param label The text to display in the chip
 * @param isSelected Whether the filter is active
 * @param onToggle Callback when the chip is clicked
 * @param leadingIcon Optional icon to display
 * @param modifier Modifier for the chip
 */
@Composable
fun SmartyFilterChip(
    label: String,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    SmartyChip(
        label = label,
        onClick = { onToggle(!isSelected) },
        leadingIcon = leadingIcon,
        isSelected = isSelected,
        variant = ChipVariant.Category,
        size = ChipSize.Medium,
        modifier = modifier
    )
}

/**
 * Convenience composable for a tag chip with optional removal.
 *
 * @param label The tag text
 * @param onRemove Optional callback when the remove button is clicked
 * @param removeIcon Icon for the remove action
 * @param modifier Modifier for the chip
 */
@Composable
fun SmartyTagChip(
    label: String,
    onRemove: (() -> Unit)? = null,
    removeIcon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    SmartyChip(
        label = label,
        trailingIcon = removeIcon,
        onTrailingClick = onRemove,
        variant = ChipVariant.Muted,
        size = ChipSize.Small,
        modifier = modifier
    )
}
