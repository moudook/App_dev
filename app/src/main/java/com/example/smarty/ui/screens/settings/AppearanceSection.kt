package com.example.smarty.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor

/**
 * Appearance settings section for customizing the app's visual appearance.
 *
 * Provides controls for:
 * - Dark/Light theme toggle
 * - Accent color selection (if supported)
 * - Future: Font size, display density, etc.
 *
 * @param isDarkTheme Current theme state (true = dark, false = light)
 * @param onThemeChange Callback when theme is toggled
 * @param accentColor Currently selected accent color (optional)
 * @param onAccentColorChange Callback when accent color is changed (optional)
 * @param availableAccentColors List of available accent colors to choose from
 * @param modifier Modifier for the section container
 */
@Composable
fun AppearanceSection(
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    accentColor: Color? = null,
    onAccentColorChange: ((Color) -> Unit)? = null,
    availableAccentColors: List<Color> = defaultAccentColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Theme Toggle
        ThemeToggleRow(
            isDarkTheme = isDarkTheme,
            onThemeChange = onThemeChange
        )

        // Accent Color Selector (only shown if callback is provided)
        if (onAccentColorChange != null && accentColor != null) {
            AccentColorSelector(
                selectedColor = accentColor,
                availableColors = availableAccentColors,
                onColorSelected = onAccentColorChange
            )
        }
    }
}

/**
 * Theme toggle row with dark/light mode switch.
 */
@Composable
private fun ThemeToggleRow(
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val accentColor = LocalAccentColor.current

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onThemeChange(!isDarkTheme) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isDarkTheme) "Dark mode" else "Light mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Switch(
                checked = isDarkTheme,
                onCheckedChange = onThemeChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accentColor,
                    checkedTrackColor = accentColor.copy(alpha = 0.3f)
                )
            )
        }
    }
}

/**
 * Accent color selector with color preview circles.
 */
@Composable
private fun AccentColorSelector(
    selectedColor: Color,
    availableColors: List<Color>,
    onColorSelected: (Color) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Accent Color",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            availableColors.forEach { color ->
                ColorCircle(
                    color = color,
                    isSelected = color == selectedColor,
                    onClick = { onColorSelected(color) }
                )
            }
        }
    }
}

/**
 * Individual color circle for accent color selection.
 */
@Composable
private fun ColorCircle(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Default accent colors available for selection.
 */
val defaultAccentColors = listOf(
    Color(0xFF6750A4), // Purple (Material You default)
    Color(0xFF0061A4), // Blue
    Color(0xFF006E1C), // Green
    Color(0xFFBA1A1A), // Red
    Color(0xFFFF6B00), // Orange
    Color(0xFF7D5260), // Pink/Mauve
)
