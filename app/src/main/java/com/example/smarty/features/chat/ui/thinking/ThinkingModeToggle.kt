package com.example.smarty.features.chat.ui.thinking

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.outlined.Assistant
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Toggle button for controlling thinking/reasoning mode.
 *
 * When enabled: Shows assistant icon (Filled) - model displays reasoning
 * When disabled: Shows assistant icon (Outlined) - model skips thinking for speed
 *
 * Used with reasoning-capable models that support <think> tags.
 *
 * @param isEnabled Current state of thinking mode
 * @param onToggle Callback when user toggles the mode
 * @param modifier Optional modifier for the button
 */
@Composable
fun ThinkingModeToggle(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon =
        if (isEnabled) {
            Icons.Filled.Assistant
        } else {
            Icons.Outlined.Assistant
        }

    val desc =
        if (isEnabled) {
            "thinking_mode_on"
        } else {
            "thinking_mode_off"
        }

    val tintColor =
        if (isEnabled) {
            MaterialTheme.colorScheme.primary // Highlighted when enabled
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) // Dimmed when disabled
        }

    IconButton(
        onClick = onToggle,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tintColor,
            modifier = Modifier.size(24.dp),
        )
    }
}
