package com.example.smarty.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.LocalShapes

/**
 * =============================================================================
 * COMPACT ICON BUTTON COMPONENT
 * =============================================================================
 *
 * A reusable, compact icon button with consistent sizing across the application.
 * Replaces scattered icon button implementations with a unified component.
 *
 * Features:
 * - Four size options (Small, Medium, Large, XL)
 * - Optional background color
 * - Customizable icon tint
 * - Accessibility support with content descriptions
 * - Consistent corner radius from design system
 * - Ripple feedback on interaction
 *
 * Size Specifications:
 * - Small:  24dp container, 14dp icon
 * - Medium: 32dp container, 18dp icon
 * - Large:  44dp container, 22dp icon
 * - XL:     48dp container, 24dp icon
 *
 * =============================================================================
 */

/**
 * Size options for CompactIconButton.
 * Each size defines container dimensions and icon size proportionally.
 */
enum class IconButtonSize {
    /** 24dp container, 14dp icon - for dense layouts */
    Small,

    /** 32dp container, 18dp icon - standard size */
    Medium,

    /** 44dp container, 22dp icon - prominent actions */
    Large,

    /** 48dp container, 24dp icon - primary actions */
    XL,
}

/**
 * Size configuration data class for internal use.
 */
private data class IconButtonSizeConfig(
    val containerSize: Dp,
    val iconSize: Dp,
)

/**
 * A compact icon button with customizable size, color, and background.
 *
 * @param icon The icon to display
 * @param onClick Callback when the button is clicked
 * @param modifier Modifier for the button container
 * @param size Size variant of the button
 * @param tint Color for the icon. Defaults to accent color.
 * @param backgroundColor Optional background color. If null, no background is shown.
 * @param contentDescription Accessibility description. Required for accessibility.
 * @param enabled Whether the button is enabled and clickable
 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: IconButtonSize = IconButtonSize.Medium,
    tint: Color = LocalAccentColor.current,
    backgroundColor: Color? = null,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val shapes = LocalShapes.current

    // Get size configuration
    val sizeConfig =
        remember(size) {
            when (size) {
                IconButtonSize.Small ->
                    IconButtonSizeConfig(
                        containerSize = 24.dp,
                        iconSize = IconSize.small,
                    )
                IconButtonSize.Medium ->
                    IconButtonSizeConfig(
                        containerSize = 32.dp,
                        iconSize = IconSize.medium,
                    )
                IconButtonSize.Large ->
                    IconButtonSizeConfig(
                        containerSize = 44.dp,
                        iconSize = IconSize.large,
                    )
                IconButtonSize.XL ->
                    IconButtonSizeConfig(
                        containerSize = IconSize.container,
                        iconSize = IconSize.xl,
                    )
            }
        }

    // Calculate effective tint based on enabled state
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.38f)
    val effectiveBackgroundColor =
        backgroundColor?.let {
            if (enabled) it else it.copy(alpha = 0.38f)
        }

    Box(
        modifier =
            modifier
                .size(sizeConfig.containerSize)
                .clip(shapes.iconButton)
                .then(
                    if (effectiveBackgroundColor != null) {
                        Modifier.background(effectiveBackgroundColor)
                    } else {
                        Modifier
                    },
                ).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication =
                        ripple(
                            radius = sizeConfig.containerSize / 2,
                        ),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    role = Role.Button
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(sizeConfig.iconSize),
            tint = effectiveTint,
        )
    }
}

/**
 * A circular variant of CompactIconButton with full background.
 * Useful for FAB-like actions or prominent buttons.
 *
 * @param icon The icon to display
 * @param onClick Callback when the button is clicked
 * @param modifier Modifier for the button container
 * @param size Size variant of the button
 * @param iconTint Color for the icon
 * @param backgroundColor Background color for the circle
 * @param contentDescription Accessibility description
 * @param enabled Whether the button is enabled
 */
@Composable
fun CircularIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: IconButtonSize = IconButtonSize.Large,
    iconTint: Color = Color.White,
    backgroundColor: Color = LocalAccentColor.current,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    val shapes = LocalShapes.current

    // Get size configuration
    val sizeConfig =
        remember(size) {
            when (size) {
                IconButtonSize.Small ->
                    IconButtonSizeConfig(
                        containerSize = 24.dp,
                        iconSize = IconSize.small,
                    )
                IconButtonSize.Medium ->
                    IconButtonSizeConfig(
                        containerSize = 32.dp,
                        iconSize = IconSize.medium,
                    )
                IconButtonSize.Large ->
                    IconButtonSizeConfig(
                        containerSize = 44.dp,
                        iconSize = IconSize.large,
                    )
                IconButtonSize.XL ->
                    IconButtonSizeConfig(
                        containerSize = IconSize.container,
                        iconSize = IconSize.xl,
                    )
            }
        }

    // Calculate effective colors based on enabled state
    val effectiveIconTint = if (enabled) iconTint else iconTint.copy(alpha = 0.38f)
    val effectiveBackgroundColor = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.38f)

    Box(
        modifier =
            modifier
                .size(sizeConfig.containerSize)
                .clip(shapes.avatar) // Circular shape
                .background(effectiveBackgroundColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication =
                        ripple(
                            radius = sizeConfig.containerSize / 2,
                        ),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics {
                    role = Role.Button
                    if (contentDescription != null) {
                        this.contentDescription = contentDescription
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(sizeConfig.iconSize),
            tint = effectiveIconTint,
        )
    }
}

/**
 * A toggle-style icon button that shows selected state.
 *
 * @param icon The icon to display
 * @param isSelected Whether the button is in selected state
 * @param onToggle Callback when the button is toggled
 * @param modifier Modifier for the button container
 * @param size Size variant of the button
 * @param selectedTint Icon tint when selected
 * @param unselectedTint Icon tint when not selected
 * @param selectedBackground Background when selected
 * @param contentDescription Accessibility description
 * @param enabled Whether the button is enabled
 */
@Composable
fun ToggleIconButton(
    icon: ImageVector,
    isSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: IconButtonSize = IconButtonSize.Medium,
    selectedTint: Color = LocalAccentColor.current,
    unselectedTint: Color = LocalAccentColor.current.copy(alpha = 0.6f),
    selectedBackground: Color = LocalAccentColor.current.copy(alpha = 0.12f),
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    CompactIconButton(
        icon = icon,
        onClick = { onToggle(!isSelected) },
        modifier = modifier,
        size = size,
        tint = if (isSelected) selectedTint else unselectedTint,
        backgroundColor = if (isSelected) selectedBackground else null,
        contentDescription = contentDescription,
        enabled = enabled,
    )
}
