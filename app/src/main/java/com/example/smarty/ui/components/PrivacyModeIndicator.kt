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
import com.example.smarty.ui.utils.AnimationLifecycleState
import com.example.smarty.ui.utils.rememberAnimationLifecycleState

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

    // LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val scale = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "privacy_pulse")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_scale"
        )
        animatedScale
    } else {
        1.05f // Static mid-point value
    }

    val alpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "privacy_pulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )
        animatedAlpha
    } else {
        0.85f // Static mid-point value
    }

    Row(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Security,
            contentDescription = "full_privacy_mode_active",
            tint = MaterialTheme.colorScheme.error.copy(alpha = alpha),
            modifier = Modifier
                .size(20.dp)
                .scale(scale)
        )

        if (showLabel) {
            Text(
                text = "full_privacy_mode",
                color = MaterialTheme.colorScheme.error,
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
        contentDescription = "full_privacy_note",
        tint = MaterialTheme.colorScheme.error,
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

    // LIFECYCLE AWARE
    val lifecycleState by rememberAnimationLifecycleState()
    val shouldAnimate = lifecycleState == AnimationLifecycleState.RUNNING

    val bgAlpha = if (shouldAnimate) {
        val infiniteTransition = rememberInfiniteTransition(label = "banner_pulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bg_alpha"
        )
        animatedAlpha
    } else {
        0.15f // Static mid-point value
    }

    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.error.copy(alpha = bgAlpha),
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
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )

            Text(
                text = "full_privacy_mode_no_ai_processing",
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
