package com.example.smarty.ui.components

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
 * Used with reasoning models like Falcon-H1R-7B that support <think> tags.
 *
 * @param isEnabled Current state of thinking mode
 * @param onToggle Callback when user toggles the mode
 * @param modifier Optional modifier for the button
 */
@Composable
fun ThinkingModeToggle(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (isEnabled) {
                Icons.Filled.Assistant
            } else {
                Icons.Outlined.Assistant
            },
            contentDescription = if (isEnabled) {
                "thinking_mode_on"
            } else {
                "thinking_mode_off"
            },
            tint = if (isEnabled) {
                MaterialTheme.colorScheme.primary  // Highlighted when enabled
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)  // Dimmed when disabled
            },
            modifier = Modifier.size(24.dp)
        )
    }
}
