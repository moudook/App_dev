package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.SafetyOrange
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.Alpha

// ═══════════════════════════════════════════════════════════════════════════════
// ENHANCED GLASSMORPHISM COLORS (Matching JarvisInputField)
// ═══════════════════════════════════════════════════════════════════════════════
private val GlassBackgroundLight = Color(0xBBFFFFFF)  // White with 73% opacity
private val GlassBackgroundDark = Color(0xB0181822)   // Dark with 69% opacity
private val GlassBlueTintLight = Color(0x220066FF)    // Blue tint 13% for light
private val GlassBlueTintDark = Color(0x332979FF)     // Blue tint 20% for dark
private val GlassBorderLight = Color(0x550066FF)      // Blue border 33% for light
private val GlassBorderDark = Color(0x662979FF)       // Blue border 40% for dark
private val GlassInnerGlow = Color(0x1AFFFFFF)        // Stronger white inner glow (10%)

private val PILL_HEIGHT = 44.dp
private val PILL_CORNER_RADIUS = 22.dp

/**
 * Floating action bar with multiple action buttons.
 * Used at the bottom of detail screens like KnowledgeCard.
 * Redesigned to match Input Block aesthetics (3 separate pills).
 */
@Composable
fun FloatingActionBar(
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    isEditing: Boolean = false,
    // @Mention: Ask AI about this note
    onAskAI: (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ComponentSpacing.screenPadding),
            horizontalArrangement = Arrangement.Center, // Centered circles
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingActionPill(
                icon = if (isEditing) Icons.Default.Check else Icons.Outlined.Edit,
                onClick = onEdit,
                tint = if (isEditing) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isEditing) {
                Spacer(modifier = Modifier.width(24.dp))

                FloatingActionPill(
                    icon = Icons.Outlined.Archive,
                    onClick = onArchive,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(24.dp))

                FloatingActionPill(
                    icon = Icons.Outlined.Delete,
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Ask AI button (for @mention quick reference)
                if (onAskAI != null) {
                    Spacer(modifier = Modifier.width(24.dp))

                    FloatingActionPill(
                        icon = Icons.Outlined.AutoAwesome,
                        onClick = onAskAI,
                        tint = LocalAccentColor.current  // Use accent color to highlight AI action
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingActionPill(
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color,
    modifier: Modifier = Modifier
) {
    // Glassmorphism: Determine colors based on theme
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val glassBackground = if (isDark) GlassBackgroundDark else GlassBackgroundLight
    val glassTint = if (isDark) GlassBlueTintDark else GlassBlueTintLight
    val glassBorder = if (isDark) GlassBorderDark else GlassBorderLight
    
    // Glassmorphism container with layered focus/press states
    Box(
        modifier = modifier
            .size(PILL_HEIGHT) // Fixed size for Circle
            .shadow(
                elevation = 6.dp, 
                shape = CircleShape, // Use CircleShape explicitly
                spotColor = tint.copy(alpha = 0.1f),
                ambientColor = tint.copy(alpha = 0.05f)
            )
            .clip(CircleShape)
            .background(glassBackground)
            .background(glassTint)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(GlassInnerGlow, Color.Transparent, Color.Transparent)
                )
            )
            .border(
                width = 0.5.dp,
                color = glassBorder,
                shape = CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = tint),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Simplified floating action bar for selection mode.
 */
@Composable
fun SelectionFloatingBar(
    selectedCount: Int,
    onPin: () -> Unit,
    onShare: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(ComponentSpacing.sheetCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = Alpha.medium)
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection count
            Text(
                text = "$selectedCount selected",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = LocalAccentColor.current
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Actions
            IconButton(onClick = onPin, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pin",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = "Archive",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = SafetyOrange
                )
            }
        }
    }
}
