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
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.screens.settings.formatCacheSize
import com.example.smarty.ui.theme.LocalShapes
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
    onClearMemorySyncResult: () -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<com.example.smarty.calendar.GoogleCalendarSyncManager.DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {}
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
    var showCalendarSelectorSheet by remember { mutableStateOf(false) }

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
                            text = stringResource(R.string.ai_status),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = stringResource(R.string.active),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.4.sp
                                ),
                                color = accentColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.adjust_your_ai_and_voice_settings),
                        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showAIConfigSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Text(
                            stringResource(R.string.manage_models),
                            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.2.sp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- GROUP 1: PERSONALIZATION ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.Assistant,
                    label = stringResource(R.string.memory),
                    value = if (aiMemories.isEmpty()) null else "${aiMemories.size}",
                    onClick = { showAIMemorySheet = true },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Default.CloudSync,
                    label = stringResource(R.string.backup),
                    onClick = { showBackupSheet = true },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsSwitch(
                    icon = Icons.Filled.Sync,
                    label = stringResource(R.string.google_calendar_sync),
                    checked = isCalendarSyncEnabled,
                    onCheckedChange = {
                        onSetCalendarSyncEnabled(it)
                        if (it) onLoadDeviceCalendars()
                    },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f),
                    showDivider = isCalendarSyncEnabled
                )
                if (isCalendarSyncEnabled) {
                    val selectedCalendar = deviceCalendars.find { it.id == targetCalendarId }
                    SettingsItem(
                        icon = Icons.Filled.Event,
                        label = stringResource(R.string.default_calendar),
                        value = selectedCalendar?.displayName?.lowercase() ?: stringResource(R.string.select),
                        onClick = {
                            onLoadDeviceCalendars()
                            showCalendarSelectorSheet = true
                        },
                        showDivider = false,
                        iconColor = accentColor,
                        containerColor = accentColor.copy(alpha = 0.1f)
                    )
                }
            }
        }

        // --- GROUP 2: APP SETTINGS ---
        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Default.Brightness4,
                    label = stringResource(R.string.dark_mode),
                    checked = isDarkTheme,
                    onCheckedChange = onToggleTheme,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Default.Archive,
                    label = stringResource(R.string.archives),
                    onClick = { showArchiveSheet = true },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.Default.Vibration,
                    label = stringResource(R.string.shake_sensitivity),
                    value = if (shakeSensitivity < 0.3f) stringResource(R.string.low) else if (shakeSensitivity < 0.7f) stringResource(R.string.medium) else stringResource(R.string.high),
                    onClick = { showShakeSensitivitySheet = true },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Default.Assistant,
                    label = stringResource(R.string.default_assistant),
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
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
            }
        }

        // --- GROUP 3: SYSTEM ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.clear_cache),
                    value = formatCacheSize(cacheSizeBytes).lowercase(),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = stringResource(R.string.sign_out),
                    onClick = onSignOut,
                    textColor = MaterialTheme.colorScheme.error,
                    iconColor = MaterialTheme.colorScheme.error,
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
                    text = stringResource(R.string.smarty_version).lowercase(),
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
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Vibration,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.shake_sensitivity),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.adjust_shake_gesture),
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
                            text = stringResource(R.string.low),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.less_sensitive),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.high),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.more_sensitive),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }

    // Calendar Selector Sheet
    if (showCalendarSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCalendarSelectorSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_default_calendar),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(deviceCalendars.size) { index ->
                        val calendar = deviceCalendars[index]
                        val isSelected = calendar.id == targetCalendarId

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSetTargetCalendarId(calendar.id)
                                    showCalendarSelectorSheet = false
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, accentColor) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(calendar.color?.let { Color(it) } ?: Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = calendar.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = calendar.accountName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.selected),
                                        tint = accentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (deviceCalendars.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_calendars_found_or_permission_denied),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
