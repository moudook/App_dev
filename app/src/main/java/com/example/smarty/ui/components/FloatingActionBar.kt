package com.example.smarty.ui.components

import androidx.compose.animation.*
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.Alpha
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.softCardShadow

// 
// SOFT MINIMALIST STYLES
// 

private val PILL_HEIGHT = 44.dp

/**
 * Floating action bar with multiple action buttons.
 * Used at the bottom of detail screens like KnowledgeCard.
 * Redesigned to match the Soft Minimalist aesthetic (clean, solid, soft shadows).
 */
@Composable
fun FloatingActionBar(
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    isEditing: Boolean = false,
    // @Mention: Ask Smarty about this note
    onAskSmarty: (() -> Unit)? = null
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
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingActionPill(
                icon = if (isEditing) Icons.Default.CheckCircle else Icons.Default.Edit,
                onClick = onEdit,
                tint = if (isEditing) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = if (isEditing) stringResource(R.string.save) else stringResource(R.string.edit_note)
            )

            if (!isEditing) {
                Spacer(modifier = Modifier.width(20.dp))

                FloatingActionPill(
                    icon = Icons.Default.Archive,
                    onClick = onArchive,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = stringResource(R.string.archive)
                )

                Spacer(modifier = Modifier.width(20.dp))

                FloatingActionPill(
                    icon = Icons.Default.DeleteOutline,
                    onClick = onDelete,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    contentDescription = stringResource(R.string.delete)
                )

                // Ask Smarty button (for @mention quick reference)
                if (onAskSmarty != null) {
                    Spacer(modifier = Modifier.width(24.dp))

                    FloatingActionPill(
                        icon = Icons.Default.AutoAwesome,
                        onClick = onAskSmarty,
                        tint = LocalAccentColor.current,
                        contentDescription = stringResource(R.string.ask_ai)
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
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    // Soft Minimalist: Solid background with soft shadow
    // No glassmorphism, no blurs, no complex borders

    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val backgroundColor = if (isDark) Color(0xFF2C2C35) else Color(0xFFFCFCFD)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE5E5EA)

    Box(
        modifier = modifier
            .size(PILL_HEIGHT)
            .softCardShadow(
                elevation = 6.dp,
                shape = CircleShape,
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(CircleShape)
            .background(backgroundColor)
            .border(
                width = 1.dp,
                color = borderColor,
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
            contentDescription = contentDescription,
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
    // Apply soft shadow to the selection bar as well
    val isDark = !MaterialTheme.colorScheme.surface.luminance().let { it > 0.5f }
    val backgroundColor = if (isDark) Color(0xFF2C2C35) else Color(0xFFFCFCFD)
    val borderColor = if (isDark) Color(0xFF3C3C45) else Color(0xFFE5E5EA)

    Surface(
        shape = RoundedCornerShape(ComponentSpacing.sheetCornerRadius),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.softCardShadow(
            elevation = 12.dp,
            shape = RoundedCornerShape(ComponentSpacing.sheetCornerRadius),
            spotColor = Color.Black.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection count
            Text(
                text = stringResource(R.string.selected_count, selectedCount),
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
                    contentDescription = stringResource(R.string.pin),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.share),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onArchive, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Archive,
                    contentDescription = stringResource(R.string.archive),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
