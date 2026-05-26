package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.DarkBorder2
import com.example.smarty.ui.theme.DarkSurfaceElevated2
import com.example.smarty.ui.theme.SemanticColors
import com.example.smarty.ui.theme.SoftBackground
import com.example.smarty.ui.theme.SubtleBorder
import com.example.smarty.ui.theme.softCardShadow

/**
 * Selection mode toolbar for bulk note operations.
 *
 * Extracted from InputStreamScreen to improve code organization.
 * Provides actions for selecting, archiving, deleting, pinning, and sharing notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionModeToolbar(
    selectedCount: Int,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onPinSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onArchiveSelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasSelection = selectedCount > 0

    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val backgroundColor = if (isDark) DarkSurfaceElevated2 else SoftBackground
    val borderColor = if (isDark) DarkBorder2 else SubtleBorder

    Surface(
        shape = RoundedCornerShape(ComponentSpacing.sheetCornerRadius),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .softCardShadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(ComponentSpacing.sheetCornerRadius),
                spotColor = Color.Black.copy(alpha = 0.15f)
            )
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Row 1: Header (Cancel, Count, Select All)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cancel / Clear selection button
                IconButton(
                    onClick = onClearSelection,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Selection count
                Text(
                    text = stringResource(R.string.selected_count, selectedCount),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = LocalAccentColor.current,
                    modifier = Modifier.weight(1f)
                )

                // Select All button
                Surface(
                    onClick = onSelectAll,
                    shape = RoundedCornerShape(12.dp),
                    color = LocalAccentColor.current.copy(alpha = 0.1f),
                    modifier = Modifier.height(32.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = stringResource(R.string.select_all),
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "All",
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalAccentColor.current
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.padding(bottom = 12.dp))

            // Row 2: Action Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SelectionActionChip(
                    icon = Icons.Default.PushPin,
                    label = "Pin",
                    tint = LocalAccentColor.current,
                    enabled = hasSelection,
                    onClick = onPinSelected
                )
                SelectionActionChip(
                    icon = Icons.Default.Share,
                    label = "Share",
                    tint = LocalAccentColor.current,
                    enabled = hasSelection,
                    onClick = onShareSelected
                )
                SelectionActionChip(
                    icon = Icons.Default.Archive,
                    label = "Archive",
                    tint = LocalAccentColor.current,
                    enabled = hasSelection,
                    onClick = onArchiveSelected
                )
                SelectionActionChip(
                    icon = Icons.Default.DeleteOutline,
                    label = "Delete",
                    tint = SemanticColors.error,
                    enabled = hasSelection,
                    onClick = onDeleteSelected
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val currentTint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val bgColor = if (enabled) tint.copy(alpha = 0.1f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        modifier = Modifier.height(36.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = currentTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = currentTint)
        }
    }
}

