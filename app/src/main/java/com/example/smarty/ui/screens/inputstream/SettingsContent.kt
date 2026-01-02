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
    // Security
    isPinConfigured: Boolean,
    onRemovePin: () -> Unit,
    // Theme
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Tavily
    tavilyApiKey: String?,
    onSetTavilyApiKey: (String?) -> Unit,
    // Cache
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean,
    // Shake
    shakeSensitivity: Float,
    onShakeSensitivityChange: (Float) -> Unit,
    // Groq stats
    groqKeyUsageStats: List<KeyUsageStats>,
    // Voice
    isVoiceEnrolled: Boolean,
    onDeleteVoiceFingerprint: () -> Unit,
    onRetrainVoice: () -> Unit,
    // Models
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>,
    // Sign out
    onSignOut: () -> Unit,
    // Embedded content for sub-sheets
    aiConfigContent: @Composable (() -> Unit) -> Unit,
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    pinSetupContent: @Composable (() -> Unit) -> Unit,
    pinChangeContent: @Composable (() -> Unit) -> Unit,
    // Content padding
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val accentColor = LocalAccentColor.current
    val context = LocalContext.current
    val shapes = LocalShapes.current


    // Sub-sheet states
    var showAIConfigSheet by remember { mutableStateOf(false) }
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showPinSetupSheet by remember { mutableStateOf(false) }
    var showPinChangeSheet by remember { mutableStateOf(false) }
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showVoiceFingerprintSheet by remember { mutableStateOf(false) }
    var showRemovePinDialog by remember { mutableStateOf(false) }

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
                            text = "AI Intelligence",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "ACTIVE",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = accentColor
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Configure your AI models and voice settings to unlock full potential.",
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
                    icon = Icons.Outlined.Person,
                    label = if (isVoiceEnrolled) "Voice ID" else "Set Up Voice ID",
                    onClick = {
                        if (isVoiceEnrolled) showVoiceFingerprintSheet = true
                        else onRetrainVoice()
                    }
                )
                SettingsItem(
                    icon = Icons.Outlined.Security,
                    label = if (isPinConfigured) "PIN Security" else "Set Up PIN",
                    onClick = {
                        if (isPinConfigured) showPinChangeSheet = true
                        else showPinSetupSheet = true
                    }
                )
                 SettingsItem(
                    icon = Icons.Outlined.Payments, // Using Payments for "Subscription & Billing" lookalike or Data
                    label = "Backup & Sync",
                    onClick = { showBackupSheet = true },
                    showDivider = false
                )
            }
        }

        // --- GROUP 2: APP SETTINGS ---
        item {
            SettingsCard {
                SettingsSwitch(
                    icon = Icons.Outlined.DarkMode,
                    label = "Dark Mode",
                    checked = isDarkTheme,
                    onCheckedChange = onToggleTheme
                )
                SettingsItem(
                    icon = Icons.Outlined.Archive,
                    label = "Archives",
                    onClick = { showArchiveSheet = true }
                )
                 SettingsItem(
                    icon = Icons.Outlined.Vibration,
                    label = "Shake Sensitivity",
                    value = if (shakeSensitivity < 0.3f) "Low" else if (shakeSensitivity < 0.7f) "Med" else "High",
                    onClick = { showShakeSensitivitySheet = true }
                )
                SettingsItem(
                    icon = Icons.Outlined.Assistant,
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
                    showDivider = false
                )
            }
        }
        
        // --- GROUP 3: SYSTEM ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Outlined.CleaningServices,
                    label = "Clear Cache",
                    value = formatCacheSize(cacheSizeBytes),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0
                )
                 SettingsItem(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = "Sign Out",
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
                    text = "Loum v1.1.0",
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

    // PIN Setup Sheet
    if (showPinSetupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPinSetupSheet = false },
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
                pinSetupContent { showPinSetupSheet = false }
            }
        }
    }

    // PIN Change Sheet
    if (showPinChangeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPinChangeSheet = false },
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
                pinChangeContent { showPinChangeSheet = false }
            }
        }
    }

    // Voice Fingerprint Sheet
    if (showVoiceFingerprintSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceFingerprintSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            com.example.smarty.ui.screens.settings.VoiceFingerprintSheetContent(
                isVoiceEnrolled = isVoiceEnrolled,
                onRetrainVoice = onRetrainVoice,
                onDeleteVoice = onDeleteVoiceFingerprint,
                onDismiss = { showVoiceFingerprintSheet = false }
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
                Text(
                    text = "Shake Sensitivity",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Adjust sensitivity for shake gesture",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                com.example.smarty.ui.components.ShakeSensitivityControl(
                    sensitivity = shakeSensitivity,
                    onSensitivityChange = onShakeSensitivityChange,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }

    // Remove PIN Dialog
    if (showRemovePinDialog) {
        AlertDialog(
            onDismissRequest = { showRemovePinDialog = false },
            title = { Text("Remove PIN?") },
            text = { Text("Anyone with access to your device will be able to view your notes.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemovePin()
                    showRemovePinDialog = false
                }) {
                    Text("Remove", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePinDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = shapes.cardMedium
        )
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
    showChevron: Boolean = true,
    showDivider: Boolean = false,
    enabled: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconColor else iconColor.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
            
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
                modifier = Modifier.padding(start = 56.dp, end = 20.dp),
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
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
            
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
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.scale(0.8f) // Slightly smaller for elegance
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 56.dp, end = 20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
            )
        }
    }
}
