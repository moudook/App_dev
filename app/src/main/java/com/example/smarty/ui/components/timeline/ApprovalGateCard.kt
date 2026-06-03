package com.example.smarty.ui.components.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.utils.ThemeAwareColors

// ═══════════════════════════════════════════════════════════════════════
// APPROVAL GATE CARD
// ═══════════════════════════════════════════════════════════════════════
// Rendered when the agent stream is paused waiting for user approval
// before executing a tool. Emitted by the server via ApprovalRequested
// and rendered here by the TimelineNodeAggregator → TimelineNode.ApprovalGate.
//
// Two input modes:
//  a) Boolean approve/deny buttons  ←  for bash, file writes, device actions
//  b) Free-text input + Submit     ←  for ask_user, confirmation dialogs
//
// When the user acts, ChatViewModel.callApproval() sends the response back
// to the server via POST /api/v1/chat/events/approval which resumes the
// OpenCode daemon stream.

/**
 * Expanded state tracks the approval card's own fold/unfold animation.
 */
private data class PendingApproval(
    val node: TimelineNode.ApprovalGate,
    val paid: Boolean,
)

@Composable
fun ApprovalGateCard(
    node: TimelineNode.ApprovalGate,
    isStreaming: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onTextSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccentColor.current
    val statusColor =
        when (node.status) {
            TimelineNode.ApprovalGate.Status.PENDING -> ThemeAwareColors.warningColor()
            TimelineNode.ApprovalGate.Status.GRANTED -> ThemeAwareColors.successColor()
            TimelineNode.ApprovalGate.Status.DENIED -> ThemeAwareColors.errorColor()
        }

    val animatedColor by animateColorAsState(targetValue = statusColor, label = "approval-border")

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ),
        border = BorderStroke(1.5.dp, animatedColor),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // ── Header pill ──────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(
                                if (node.status == TimelineNode.ApprovalGate.Status.PENDING) animatedColor else animatedColor,
                                shape = CircleShape,
                            ),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text =
                        when (node.status) {
                            TimelineNode.ApprovalGate.Status.PENDING -> "Waiting for you"
                            TimelineNode.ApprovalGate.Status.GRANTED -> "Approved"
                            TimelineNode.ApprovalGate.Status.DENIED -> "Denied"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        when (node.status) {
                            TimelineNode.ApprovalGate.Status.PENDING -> ThemeAwareColors.warningColor()
                            TimelineNode.ApprovalGate.Status.GRANTED -> ThemeAwareColors.successColor()
                            TimelineNode.ApprovalGate.Status.DENIED -> ThemeAwareColors.errorColor()
                        },
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tool: ${node.toolId.take(12)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Resolved check-icon
                if (node.status != TimelineNode.ApprovalGate.Status.PENDING) {
                    Icon(
                        imageVector =
                            if (node.status == TimelineNode.ApprovalGate.Status.GRANTED) {
                                Icons.Outlined.CheckCircle
                            } else {
                                Icons.Default.Close
                            },
                        contentDescription = null,
                        tint =
                            if (node.status == TimelineNode.ApprovalGate.Status.GRANTED) {
                                ThemeAwareColors.successColor()
                            } else {
                                ThemeAwareColors.errorColor()
                            },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ── Tool name + description ──────────────────────────
            Text(
                text = node.toolId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // ── Approval buttons or text input ────────────────────
            when {
                node.status == TimelineNode.ApprovalGate.Status.PENDING -> {
                    ApproveDenyButtons(
                        onApprove = onApprove,
                        onDeny = onDeny,
                    )
                }
                node.status == TimelineNode.ApprovalGate.Status.GRANTED -> {
                    ApprovedSummary()
                }
                node.status == TimelineNode.ApprovalGate.Status.DENIED -> {
                    DeniedSummary()
                }
            }
        }
    }
}

@Composable
private fun ApproveDenyButtons(
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onApprove,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = ThemeAwareColors.successColor(),
                    contentColor = Color.White,
                ),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Approve", fontWeight = FontWeight.Medium)
        }

        OutlinedButton(
            onClick = onDeny,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ThemeAwareColors.errorColor()),
            border = BorderStroke(1.5.dp, ThemeAwareColors.errorColor()),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Deny", fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ApprovedSummary() {
    Text(
        text = "You approved it. Let\'s keep going.",
        style = MaterialTheme.typography.bodySmall,
        color = ThemeAwareColors.successColor(),
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DeniedSummary() {
    Text(
        text = "Declined. I\'ll try something else.",
        style = MaterialTheme.typography.bodySmall,
        color = ThemeAwareColors.errorColor(),
        modifier = Modifier.padding(top = 4.dp),
    )
}
