package com.example.smarty.ui.components.common

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.smarty.ui.theme.ElectricBlue
import com.example.smarty.ui.theme.MinimalRed

/**
 * A highly stylized dialog component inspired by "Bento-grid" fintech UI.
 * Features deep dark backgrounds, soft gradients, and large rounded corners.
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
fun JarvisDialog(
    title: String,
    text: String = "",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    isDestructive: Boolean = false,
    confirmEnabled: Boolean = true,
    customContent: @Composable (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false) // Allow custom width
    ) {
        // Main Container "Card"
        Box(
            modifier = Modifier
                .padding(24.dp) // Outer margin
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp)) // Super rounded corners (Bento style)
                .background(
                    // Subtle vertical gradient for that "premium" dark feel
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1E1E24), // Slightly lighter top
                            Color(0xFF121216)  // Deep dark bottom
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.Start
            ) {
                // Title
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = Color.White
                )

                if (text.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    // Body
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 24.sp
                        ),
                        color = Color(0xFF9A9BA1) // Soft grey
                    )
                }

                if (customContent != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    customContent()
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel Button (Soft pill)
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2C2C35), // Dark grey pill
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(50), // Full pill
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(
                            text = dismissText,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    // Confirm Button (High contrast pill)
                    val containerColor = if (isDestructive) MinimalRed else ElectricBlue
                    val contentColor = Color.White
                    val disabledContainerColor = Color(0xFF2C2C35).copy(alpha = 0.5f)
                    val disabledContentColor = Color.White.copy(alpha = 0.3f)

                    Button(
                        onClick = onConfirm,
                        enabled = confirmEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                            disabledContainerColor = disabledContainerColor,
                            disabledContentColor = disabledContentColor
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(
                            text = confirmText,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
