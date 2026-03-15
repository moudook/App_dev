package com.example.smarty.ui.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Modern action button with icon and label
 * Used throughout the app for consistent action buttons
 */
@Composable
fun ModernActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ActionButtonColors = ActionButtonDefaults.colors()
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = 80.dp, minHeight = 80.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        enabled = enabled
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) colors.iconColor else colors.disabledIconColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) colors.labelColor else colors.disabledLabelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Compact icon button for dense layouts
 */
@Composable
fun CompactIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: ButtonSize = ButtonSize.Medium
) {
    val iconSize = when (size) {
        ButtonSize.Small -> 20.dp
        ButtonSize.Medium -> 24.dp
        ButtonSize.Large -> 28.dp
    }
    
    val containerSize = when (size) {
        ButtonSize.Small -> 36.dp
        ButtonSize.Medium -> 40.dp
        ButtonSize.Large -> 44.dp
    }
    
    IconButton(
        onClick = onClick,
        modifier = modifier.size(containerSize),
        enabled = enabled
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) 
                MaterialTheme.colorScheme.onSurfaceVariant 
            else 
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        )
    }
}

/**
 * Stat card for displaying metrics
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trend: Trend? = null
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (trend != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (trend.isPositive) 
                            Icons.AutoMirrored.Filled.TrendingUp
                        else 
                            Icons.AutoMirrored.Filled.TrendingDown,
                        contentDescription = null,
                        tint = if (trend.isPositive) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = trend.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (trend.isPositive) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Info chip for tags and categories
 */
@Composable
fun InfoChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

/**
 * Divider with optional label
 */
@Composable
fun LabeledDivider(
    label: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(color = color)
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        Spacer(modifier = Modifier.width(16.dp))
        HorizontalDivider(color = color)
    }
}

/**
 * Empty state placeholder
 */
@Composable
fun EmptyStatePlaceholder(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class ButtonSize { Small, Medium, Large }

data class Trend(val value: String, val isPositive: Boolean)

object ActionButtonDefaults {
    @Composable
    fun colors(
        containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
        disabledContainerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
        iconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledIconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f),
        labelColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledLabelColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
    ): ActionButtonColors {
        return ActionButtonColors(
            containerColor,
            disabledContainerColor,
            iconColor,
            disabledIconColor,
            labelColor,
            disabledLabelColor
        )
    }
}

data class ActionButtonColors(
    val containerColor: Color,
    val disabledContainerColor: Color,
    val iconColor: Color,
    val disabledIconColor: Color,
    val labelColor: Color,
    val disabledLabelColor: Color
)
