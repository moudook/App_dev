package com.example.smarty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.theme.LocalShapes

// Internal composition local to pass background color down to rows for seamless look
private val LocalCardContainerColor = staticCompositionLocalOf { Color.Transparent }

/**
 * Default properties for Smarty Settings components.
 * Centralized here to avoid hardcoding throughout the logic.
 */
object SmartySettingsDefaults {
    val CardPaddingHorizontal = 20.dp
    val RowMinHeight = 68.dp // Height for rows with subtitles
    val RowCompactMinHeight = 54.dp // Height for single-line rows
    val RowHorizontalPadding = 20.dp
    val RowVerticalPadding = 8.dp
    val IconSize = 24.dp
    val ChevronSize = 20.dp
    val LabelFontSize = 17.sp
    val SeparatorHeight = 2.5.dp
    val ItemCornerRadius = 3.dp

    val CardShape: CornerBasedShape
        @Composable
        @ReadOnlyComposable
        get() = LocalShapes.current.card

    val ItemShape: CornerBasedShape
        get() = RoundedCornerShape(ItemCornerRadius)

    val ContainerColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.surfaceVariant

    val SeparatorColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.outline

    val LabelColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface

    val SubtitleColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    val ChevronColor: Color
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
}

/**
 * A unified settings card container that holds one or multiple SettingsRows.
 * It applies a consistent theme-adaptive background and rounding.
 */
@Composable
fun SmartySettingsCard(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = SmartySettingsDefaults.CardPaddingHorizontal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerBackground = SmartySettingsDefaults.ContainerColor
    val separatorColor = SmartySettingsDefaults.SeparatorColor

    CompositionLocalProvider(LocalCardContainerColor provides containerBackground) {
        Column(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .clip(SmartySettingsDefaults.CardShape)
                    .background(separatorColor),
            verticalArrangement = Arrangement.spacedBy(SmartySettingsDefaults.SeparatorHeight),
        ) {
            content()
        }
    }
}

/**
 * A unified settings row component for lists and buttons.
 * Adapts its colors automatically to the current theme.
 */
@Composable
fun SmartySettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    onClick: () -> Unit = {},
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContent: @Composable (RowScope.() -> Unit)? = null,
    trailingContent: @Composable (RowScope.() -> Unit)? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val itemBackground = LocalCardContainerColor.current

    val minHeight =
        if (subtitle != null) {
            SmartySettingsDefaults.RowMinHeight
        } else {
            SmartySettingsDefaults.RowCompactMinHeight
        }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        shape = SmartySettingsDefaults.ItemShape,
        color = itemBackground,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = SmartySettingsDefaults.RowHorizontalPadding,
                        vertical = SmartySettingsDefaults.RowVerticalPadding,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingContent != null) {
                leadingContent()
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) iconColor else iconColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(SmartySettingsDefaults.IconSize),
                )
                Spacer(modifier = Modifier.width(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style =
                        MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = SmartySettingsDefaults.LabelFontSize,
                        ),
                    color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = SmartySettingsDefaults.SubtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (trailingContent != null) {
                trailingContent()
                if (showChevron) Spacer(modifier = Modifier.width(8.dp))
            }

            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = SmartySettingsDefaults.ChevronColor,
                    modifier = Modifier.size(SmartySettingsDefaults.ChevronSize),
                )
            }
        }
    }
}

/**
 * A convenience wrapper for a settings row with a toggle switch.
 */
@Composable
fun SmartySettingsSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    SmartySettingsRow(
        label = label,
        modifier = modifier,
        icon = icon,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        iconColor = iconColor,
        showChevron = false,
        enabled = enabled,
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(0.8f),
            )
        },
    )
}
