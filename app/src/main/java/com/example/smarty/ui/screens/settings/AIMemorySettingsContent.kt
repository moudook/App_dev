package com.example.smarty.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType

/**
 * =============================================================================
 * AI MEMORY SETTINGS SCREEN
 * =============================================================================
 *
 * Displays and manages AI memories stored about the user.
 * Users can view, delete individual memories, or clear all memories.
 *
 * =============================================================================
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIMemorySettingsContent(
    memories: List<AIMemory>,
    onDeleteMemory: (AIMemory) -> Unit,
    onClearAllMemories: () -> Unit,
    onDismiss: () -> Unit,
    onSyncMemories: () -> Unit = {},
    isSyncing: Boolean = false,
    syncResult: String? = null,
    unreadNotesCount: Int = 0,
    onClearSyncResult: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showClearAllDialog by remember { mutableStateOf(false) }
    var memoryToDelete by remember { mutableStateOf<AIMemory?>(null) }
    
    // Centralized Layout Container
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Centralized Minimalist Header
        Spacer(modifier = Modifier.height(24.dp))
        
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Assistant, // Systematic assistant icon
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "personal_intelligence",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Text(
            text = "${memories.size}_insights_extracted",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 2. Combined Sync & Status Action
        // Minimalist pill that expands for sync status
        Surface(
            onClick = onSyncMemories,
            enabled = !isSyncing,
            shape = RoundedCornerShape(50), // Fully rounded pill
            color = if (unreadNotesCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.height(48.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isSyncing) {
                    com.example.smarty.ui.components.CalmThinkingDots(
                        color = MaterialTheme.colorScheme.onPrimary,
                        dotSize = 3.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "analyzing_notes",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = if (unreadNotesCount > 0) Icons.Default.CloudSync else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (unreadNotesCount > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (unreadNotesCount > 0) "sync_${unreadNotesCount}_new_notes" else "memory_up_to_date",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (unreadNotesCount > 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        // Sync Result Toast (Centralized)
        AnimatedVisibility(
            visible = syncResult != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut()
        ) {
            syncResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onClearSyncResult,
                        modifier = Modifier.size(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            null,
                            tint = MaterialTheme.colorScheme.inverseOnSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // 3. Centralized Memory List
        if (memories.isEmpty()) {
            com.example.smarty.ui.components.IntelligenceEmptyState(
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(memories, key = { it.id }) { memory ->
                    MemoryChip(
                        memory = memory,
                        onDelete = { memoryToDelete = memory }
                    )
                }
                
                // Clear All at bottom of list
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(
                        onClick = { showClearAllDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "clear_learning_data",
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }

    // Delete Single Memory Dialog
    if (memoryToDelete != null) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "forget_this_insight",
            text = "the_ai_will_stop_using_this_behavioral_pattern_for_personalization",
            onConfirm = {
                memoryToDelete?.let { onDeleteMemory(it) }
                memoryToDelete = null
            },
            onDismiss = { memoryToDelete = null },
            confirmText = "forget",
            dismissText = "cancel",
            isDestructive = true
        )
    }

    // Clear All Dialog
    if (showClearAllDialog) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "reset_personal_intelligence",
            text = "all_extracted_insights_will_be_deleted_this_cannot_be_undone",
            onConfirm = {
                onClearAllMemories()
                showClearAllDialog = false
            },
            onDismiss = { showClearAllDialog = false },
            confirmText = "reset_all",
            dismissText = "cancel",
            isDestructive = true
        )
    }
}

@Composable
private fun MemoryChip(
    memory: AIMemory,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    // Dynamic coloring based strictly on Theme
    val containerColor = when(memory.type) {
        MemoryType.PREFERENCE -> MaterialTheme.colorScheme.primaryContainer
        MemoryType.PATTERN -> MaterialTheme.colorScheme.tertiaryContainer
        MemoryType.STYLE -> MaterialTheme.colorScheme.secondaryContainer
        MemoryType.FACT -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    
    val contentColor = when(memory.type) {
        MemoryType.PREFERENCE -> MaterialTheme.colorScheme.onPrimaryContainer
        MemoryType.PATTERN -> MaterialTheme.colorScheme.onTertiaryContainer
        MemoryType.STYLE -> MaterialTheme.colorScheme.onSecondaryContainer
        MemoryType.FACT -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        onClick = { expanded = !expanded },
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp), // Comfortable rounded corners
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                // Type Indicator (Small, centralized label)
                Icon(
                    imageVector = when(memory.type) {
                        MemoryType.PREFERENCE -> Icons.Default.StarOutline // Standard preference
                        MemoryType.PATTERN -> Icons.Default.Timeline // Standard pattern/graph
                        MemoryType.STYLE -> Icons.Default.Brush // Standard style/design
                        MemoryType.FACT -> Icons.Default.Info // Standard fact/info
                    },
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp).offset(y = 2.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = memory.content,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = contentColor,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (expanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = memory.type.name.lowercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.6f)
                        )
                    }
                }
                
                if (expanded) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.DeleteOutline, // Standard delete
                            contentDescription = "forget",
                            tint = contentColor.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

// Helper color import if needed
// import androidx.compose.ui.graphics.Color
// import androidx.compose.foundation.BorderStroke
