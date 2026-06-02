package com.example.smarty.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.core.domain.model.AgentStepEntry
import com.example.smarty.ui.LocalAccentColor

@Composable
fun StepTimeline(steps: List<AgentStepEntry>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        steps.forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 2.dp),
            ) {
                val (dotColor, dotIcon) =
                    when (step.stepStatus) {
                        "completed" -> Color(0xFF4CAF50) to Icons.Outlined.CheckCircle
                        "failed" -> MaterialTheme.colorScheme.error to Icons.Default.Cancel
                        else -> LocalAccentColor.current to null
                    }
                if (dotIcon != null) {
                    Icon(
                        dotIcon,
                        null,
                        tint = dotColor,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Box(
                        modifier =
                            Modifier
                                .size(7.dp)
                                .background(dotColor.copy(alpha = 0.7f), CircleShape),
                    )
                }
                Text(
                    text = step.stepTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
