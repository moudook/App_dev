package com.example.smarty.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Safety/Warning orange color for privacy indicators
 */
private val SafetyOrange = Color(0xFFFF6B00)
private val SafetyOrangeLight = Color(0xFFFF8C3D)

/**
 * Visual indicator for full privacy mode
 * Shows shield icon with pulse animation when active
 */
@Composable
fun PrivacyModeIndicator(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true
) {
    if (!isActive) return

    val infiniteTransition = rememberInfiniteTransition(label = "privacy_pulse")

    // Pulse scale animation
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // Pulse alpha animation for glow effect
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Row(
        modifier = modifier
            .background(
                color = SafetyOrange.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "Full Privacy Mode Active",
            tint = SafetyOrange.copy(alpha = alpha),
            modifier = Modifier
                .size(20.dp)
                .scale(scale)
        )

        if (showLabel) {
            Text(
                text = "Full Privacy Mode",
                color = SafetyOrange,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Compact privacy indicator for note cards
 * Just shows the shield icon without label
 */
@Composable
fun PrivacyModeIcon(
    modifier: Modifier = Modifier
) {
    Icon(
        imageVector = Icons.Default.Security,
        contentDescription = "Full privacy note",
        tint = SafetyOrange,
        modifier = modifier.size(16.dp)
    )
}

/**
 * Full privacy mode banner for share bottom sheet
 */
@Composable
fun PrivacyModeBanner(
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isActive) return

    val infiniteTransition = rememberInfiniteTransition(label = "banner_pulse")

    val bgAlpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg_alpha"
    )

    Box(
        modifier = modifier
            .background(
                color = SafetyOrange.copy(alpha = bgAlpha),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = SafetyOrange,
                modifier = Modifier.size(24.dp)
            )

            Text(
                text = "Full Privacy Mode - No AI Processing",
                color = SafetyOrange,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
