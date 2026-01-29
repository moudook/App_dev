package com.example.smarty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.Alpha

/**
 * Unified Bottom Sheet with consistent styling across the app.
 * Use this for all bottom sheets to maintain visual coherence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = LocalAccentColor.current,
    heightFraction: Float = 0.92f,
    useDarkTheme: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val isDark = !containerColor.luminance().let { it > 0.5f }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = ComponentSpacing.sheetCornerRadius, topEnd = ComponentSpacing.sheetCornerRadius),
        containerColor = containerColor,
        dragHandle = { UnifiedDragHandle(isDark = isDark) },
        modifier = Modifier.fillMaxHeight(heightFraction)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            UnifiedSheetHeader(
                title = title,
                subtitle = subtitle,
                icon = icon,
                iconTint = iconTint,
                isDark = isDark
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = Alpha.soft),
                modifier = Modifier.padding(horizontal = ComponentSpacing.sheetPadding)
            )

            content()
        }
    }
}

@Composable
private fun UnifiedDragHandle(isDark: Boolean) {
    Box(
        modifier = Modifier
            .padding(vertical = 12.dp)
            .width(ComponentSpacing.sheetDragHandleWidth)
            .height(ComponentSpacing.sheetDragHandleHeight)
            .clip(RoundedCornerShape(2.dp))
            .background(
                if (isDark) Color.White.copy(alpha = Alpha.emphasis)
                else Color.Black.copy(alpha = Alpha.moderate)
            )
    )
}

@Composable
private fun UnifiedSheetHeader(
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconTint: Color,
    isDark: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ComponentSpacing.sheetPadding, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = iconTint.copy(alpha = Alpha.light),
                modifier = Modifier.size(IconSize.massive)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(IconSize.large)
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
        }

        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color.White.copy(alpha = Alpha.heavy)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
