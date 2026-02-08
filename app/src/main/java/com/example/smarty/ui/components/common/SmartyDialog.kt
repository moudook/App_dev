package com.example.smarty.ui.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.MinimalRed
import com.example.smarty.ui.theme.softCardShadow

// Matching the Input Pill aesthetics
private val DIALOG_CORNER_RADIUS = 26.dp
private val PILL_HEIGHT = 52.dp

/**
 * A stylized dialog component matching the "Floating Pill" UI aesthetic.
 * Features soft shadows, pill-shaped corners, and subtle borders.
 *
 * @param title The main headline of the dialog.
 * @param text Supporting text or description.
 * @param onConfirm Action to take on confirmation.
 * @param onDismiss Action to take on dismissal.
 * @param confirmText Text for the confirm button.
 * @param dismissText Text for the cancel/dismiss button.
 * @param isDestructive If true, styles the confirm button with a warning color.
 */
@Composable
fun SmartyDialog(
    title: String,
    text: String = "",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.confirm),
    dismissText: String = stringResource(R.string.cancel),
    isDestructive: Boolean = false,
    confirmEnabled: Boolean = true,
    customContent: @Composable (() -> Unit)? = null
) {
    val isDark = isSystemInDarkTheme()
    val accentColor = LocalAccentColor.current

    // Match the input pill's background style
    val dialogBackground = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    // Subtle border like the input pill
    val borderColor = if (isDark) {
        Color.White.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Main Container with soft shadow like input field
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .softCardShadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(DIALOG_CORNER_RADIUS),
                    spotColor = Color.Black.copy(alpha = 0.15f)
                ),
            shape = RoundedCornerShape(DIALOG_CORNER_RADIUS),
            color = dialogBackground,
            border = BorderStroke(0.5.dp, borderColor)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Title - Clean and minimal
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (text.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // Body text
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (customContent != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    customContent()
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Row - Pill buttons matching input field height
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel Button - Subtle pill
                    val cancelBackground = if (isDark) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cancelBackground,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(DIALOG_CORNER_RADIUS),
                        modifier = Modifier
                            .weight(1f)
                            .height(PILL_HEIGHT),
                        elevation = ButtonDefaults.buttonElevation(0.dp),
                        border = BorderStroke(0.5.dp, borderColor)
                    ) {
                        Text(
                            text = dismissText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Medium
                            )
                        )
                    }

                    // Confirm Button - Accent or destructive color
                    val confirmContainerColor = if (isDestructive) MinimalRed else accentColor
                    val disabledContainerColor = cancelBackground.copy(alpha = 0.5f)
                    val disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = confirmContainerColor,
                            contentColor = Color.White,
                            disabledContainerColor = disabledContainerColor,
                            disabledContentColor = disabledContentColor
                        ),
                        shape = RoundedCornerShape(DIALOG_CORNER_RADIUS),
                        modifier = Modifier
                            .weight(1f)
                            .height(PILL_HEIGHT),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(
                            text = confirmText,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        }
    }
}
