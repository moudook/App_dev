package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.Alpha

/**
 * Reimagined Unified Bottom Sheet (The "Smart Frame").
 * 
 * Features:
 * - Glassmorphic Header with Mesh Gradient
 * - Tactile Machined Drag Handle
 * - Consistent "Shelf" height standardization
 * - Premium typography and depth
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
    val isDark = !containerColor.luminance().let { it > 0.5f }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = containerColor,
        dragHandle = { UnifiedDragHandle() },
        modifier = Modifier.fillMaxHeight(heightFraction),
        scrimColor = Color.Black.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Glassmorphic Header Area
            Box(modifier = Modifier.fillMaxWidth()) {
                // Background Mesh Glow
                Canvas(modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .blur(40.dp)
                ) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(iconTint.copy(alpha = 0.15f), Color.Transparent),
                            center = Offset(size.width * 0.1f, 0f),
                            radius = size.width * 0.6f
                        )
                    )
                }

                UnifiedSheetHeader(
                    title = title,
                    subtitle = subtitle,
                    icon = icon,
                    iconTint = iconTint,
                    isDark = isDark
                )
            }

            // Body Content Area with Dynamic Scrims
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Content
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    content()
                }

                // Top Fade Scrim (Matches PREFERRED_UI_REFERENCE logic) - Reduced height to prevent obscuration
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    containerColor,
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Bottom Fade Scrim - Reduced height
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    containerColor
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun UnifiedDragHandle() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Machined Aluminum look drag handle
        Box(
            modifier = Modifier
                .width(42.dp)
                .height(5.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                        )
                    )
                )
        ) {
            // "Grooves" detail
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) {
                    Box(
                        modifier = Modifier
                            .size(2.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    )
                }
            }
        }
    }
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
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            // Premium Icon Surface with Outer Glow
            Box(contentAlignment = Alignment.Center) {
                // Outer Glow
                Surface(
                    shape = CircleShape,
                    color = iconTint.copy(alpha = 0.12f),
                    modifier = Modifier.size(56.dp)
                ) {}
                
                // Icon Core
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = containerColor(isDark).copy(alpha = 0.8f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent))
                    ),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null, // Decorative icon - sheet title provides context
                            tint = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(18.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp // Tighter tracking for premium feel
                ),
                color = if (isDark) Color.White else Color.Black // Preference Reference: High Contrast
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelLarge.copy(
                        letterSpacing = 0.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = (if (isDark) Color.White else Color.Black).copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun containerColor(isDark: Boolean): Color {
    return if (isDark) MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp)
    else Color.White
}
