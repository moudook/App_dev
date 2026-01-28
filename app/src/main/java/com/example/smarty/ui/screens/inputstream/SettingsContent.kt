package com.example.smarty.ui.screens.inputstream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.* // Use outlined icons for the new look
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.screens.settings.formatCacheSize
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.data.model.AIMemory
import com.example.smarty.ui.screens.settings.AIMemorySettingsContent

/**
 * Inline settings content with "Chroma Studio" aesthetic.
 * Grouped into dark, rounded cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    // AI & Provider settings
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    // Theme
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Tavily
    tavilyApiKeys: List<String>,
    onAddTavilyApiKey: (String) -> Unit,
    onRemoveTavilyApiKey: (String) -> Unit,
    // Cache
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean,
    // Shake
    shakeSensitivity: Float,
    onShakeSensitivityChange: (Float) -> Unit,
    // Groq stats
    groqKeyUsageStats: List<KeyUsageStats>,
    // Models
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>,
    // Sign out
    onSignOut: () -> Unit,
    // Embedded content for sub-sheets
    aiConfigContent: @Composable (() -> Unit) -> Unit,
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    // Content padding
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    // AI Memory
    aiMemories: List<AIMemory> = emptyList(),
    onDeleteAIMemory: (AIMemory) -> Unit = {},
    onClearAllAIMemories: () -> Unit = {},
    // Memory sync
    onSyncAIMemories: () -> Unit = {},
    isMemorySyncInProgress: Boolean = false,
    memorySyncResult: String? = null,
    unreadForMemoryCount: Int = 0,
    onClearMemorySyncResult: () -> Unit = {}
) {
    val accentColor = LocalAccentColor.current
    val context = LocalContext.current
    val shapes = LocalShapes.current


    // Sub-sheet states
    var showAIConfigSheet by remember { mutableStateOf(false) }
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showAIMemorySheet by remember { mutableStateOf(false) }

    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp) // Gap between cards
    ) {
        
        // --- PRO BANNER (Simulated for AI Status) ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "AI Status",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF2196F3).copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2196F3).copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "Active",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF2196F3)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Adjust your AI and voice settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { showAIConfigSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text("Manage Models")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, 
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // --- GROUP 1: PERSONALIZATION ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Filled.AutoAwesome, // Metaphor: Constellation/Sparkles
                    label = "Memory",
                    value = if (aiMemories.isEmpty()) null else "${aiMemories.size}",
                    onClick = { showAIMemorySheet = true },
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.Filled.Anchor, // Metaphor: Anchor (Security/Grounding)
                    label = "Backup",
                    onClick = { showBackupSheet = true },
                    showDivider = false,
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
            }
        }

        // --- GROUP 2: APP SETTINGS ---
        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Filled.Contrast, // Metaphor: Eclipse (Light/Dark interplay)
                    label = "Dark Mode",
                    checked = isDarkTheme,
                    onCheckedChange = onToggleTheme,
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Filled.AllInbox, // Metaphor: Vault (Secure storage)
                    label = "Archives",
                    onClick = { showArchiveSheet = true },
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.Filled.Waves, // Metaphor: Seismograph (Motion)
                    label = "Shake sensitivity",
                    value = if (shakeSensitivity < 0.3f) "Low" else if (shakeSensitivity < 0.7f) "Med" else "High",
                    onClick = { showShakeSensitivitySheet = true },
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Filled.Explore, // Metaphor: Compass (Navigation/Guide)
                    label = "Default Assistant",
                    onClick = {
                         try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    showDivider = false,
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
            }
        }

        // --- GROUP 3: SYSTEM ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Filled.Whatshot, // Metaphor: Fire (Purge/Cleanse)
                    label = "Clear Cache",
                    value = formatCacheSize(cacheSizeBytes),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0,
                    iconColor = Color(0xFF2196F3), // Blue
                    containerColor = Color(0xFF2196F3).copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.Filled.NoAccounts, // Metaphor: Ghost (Leaving the body/machine)
                    label = "Sign Out",
                    onClick = onSignOut,
                    textColor = MaterialTheme.colorScheme.error,
                    iconColor = MaterialTheme.colorScheme.error, // Keep red for destructive
                    showChevron = false,
                    showDivider = false
                )
            }
        }

        // Bottom Banner
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Jarvis v1.1.0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // Bottom spacer for input field
        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // -- Sub-sheets --
    
    // AI Config Sheet
    if (showAIConfigSheet) {
        aiConfigContent { showAIConfigSheet = false }
    }

    // Archive Sheet
    if (showArchiveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showArchiveSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                archiveContent { showArchiveSheet = false }
            }
        }
    }

    // Backup Sheet
    if (showBackupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBackupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                backupContent { showBackupSheet = false }
            }
        }
    }


    // AI Memory Sheet
    if (showAIMemorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showAIMemorySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            AIMemorySettingsContent(
                memories = aiMemories,
                onDeleteMemory = onDeleteAIMemory,
                onClearAllMemories = onClearAllAIMemories,
                onDismiss = { showAIMemorySheet = false },
                onSyncMemories = onSyncAIMemories,
                isSyncing = isMemorySyncInProgress,
                syncResult = memorySyncResult,
                unreadNotesCount = unreadForMemoryCount,
                onClearSyncResult = onClearMemorySyncResult
            )
        }
    }

    // Shake Sensitivity Sheet
    if (showShakeSensitivitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showShakeSensitivitySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with icon - matches notecard aesthetic
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Vibration,
                        contentDescription = null,
                        tint = Color(0xFF2196F3),
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Shake sensitivity",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Adjust shake gesture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Sensitivity control in a card container
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        com.example.smarty.ui.components.ShakeSensitivityControl(
                            sensitivity = shakeSensitivity,
                            onSensitivityChange = onShakeSensitivityChange
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Labels row matching notecard style
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(horizontalAlignment = Alignment.Start) {
                        Text(
                            text = "Low",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Less sensitive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "High",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "More sensitive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

// -- Components matching the "Image" Aesthetic --

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            content = content
        )
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Transparent, // Default to transparent if not specified
    showChevron: Boolean = true,
    showDivider: Boolean = false,
    enabled: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp), // Slightly adjusted vertical padding
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Creative Icon Container using Squircle (RoundedCorner)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp)) // Modern squircle shape
                    .background(if (enabled && containerColor != Color.Transparent) containerColor else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) iconColor else iconColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = if (enabled) textColor else textColor.copy(alpha = 0.4f),
                modifier = Modifier.weight(1f)
            )

            if (value != null) {
                 Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            
            if (showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 78.dp, end = 20.dp), // Adjusted start padding for larger icon
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
            )
        }
    }
}

@Composable
private fun SettingsSwitch(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Transparent,
    showDivider: Boolean = false
) {
    val accentColor = LocalAccentColor.current
    
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
             // Creative Icon Container
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (containerColor != Color.Transparent) containerColor else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = iconColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.scale(0.8f) // Slightly smaller for elegance
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 78.dp, end = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
            )
        }
    }
}
