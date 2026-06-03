package com.example.smarty.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.utils.ThemeAwareColors

/**
 * Compact chip showing an action's result (success/fail).
 * ElevenLabs 'Badge' Style: Thin border, subtle background, crisp text.
 *
 * Performance: Colors are [remember]ed by success+isDark to avoid
 * re-allocating Color objects on every recomposition.
 */
@Composable
fun ActionResultChip(
    actionName: String,
    success: Boolean,
    summary: String,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    
    // Compute colors first (composable context)
    val successColor = ThemeAwareColors.successColor()
    val errorColor = ThemeAwareColors.errorColor()

    // Cache color computation — stable across recompositions for same inputs
    val (backgroundColor, contentColor) =
        remember(success, isDark) {
            val bg =
                if (success) {
                    if (isDark) successColor.copy(alpha = 0.15f) else successColor.copy(alpha = 0.1f)
                } else {
                    if (isDark) errorColor.copy(alpha = 0.15f) else errorColor.copy(alpha = 0.1f)
                }
            val fg = if (success) successColor else errorColor
            bg to fg
        }

    val borderColor = contentColor.copy(alpha = 0.3f)

    // Cache formatted action name
    val formattedName = remember(actionName) { formatActionName(actionName) }

    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = if (success) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = formattedName,
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                        letterSpacing = 0.sp,
                    ),
                color = contentColor,
            )
            if (summary.isNotBlank()) {
                Spacer(modifier = Modifier.width(6.dp))
                // Separator dot
                Box(
                    modifier =
                        Modifier
                            .size(3.dp)
                            .background(contentColor.copy(alpha = 0.5f), CircleShape),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = summary,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                    color = contentColor.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Converts a PascalCase action class name into a human-readable label.
 * e.g. "SearchWebAction" → "Search Web"
 */
internal fun formatActionName(actionName: String): String =
    actionName
        .replace("Action", "")
        .replace(Regex("([A-Z])"), " $1")
        .trim()
