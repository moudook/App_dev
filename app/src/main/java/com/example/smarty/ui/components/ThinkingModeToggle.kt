package com.example.smarty.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Toggle button for controlling thinking/reasoning mode.
 * 
 * When enabled: Shows brain icon (Psychology) - model displays reasoning
 * When disabled: Shows lightning icon (Bolt) - model skips thinking for speed
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
                Icons.Filled.Lightbulb  // Lightbulb - Creative Insight
            } else {
                Icons.Outlined.Bolt  // Lightning icon - fast mode (thinking OFF)
            },
            contentDescription = if (isEnabled) {
                "Thinking mode ON - Tap to disable reasoning display"
            } else {
                "Thinking mode OFF - Tap to enable reasoning display"
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
