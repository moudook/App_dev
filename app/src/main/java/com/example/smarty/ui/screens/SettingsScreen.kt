package com.example.smarty.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.screens.settings.ProviderSection
import com.example.smarty.ui.screens.settings.maskApiKey
import com.example.smarty.util.api.ApiMetrics
import com.example.smarty.util.api.KeyUsageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.smarty.ui.components.ShakeSensitivityControl
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Duolingo-style Settings Screen
 * Clean, simple, minimal scrolling required
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    isPinConfigured: Boolean,
    isDarkTheme: Boolean,
    onBackClick: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onRemovePin: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    // Tavily Web Search API
    tavilyApiKey: String? = null,
    onSetTavilyApiKey: (String?) -> Unit = {},
    // Embedded Content Slots
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    pinSetupContent: @Composable (() -> Unit) -> Unit,
    pinChangeContent: @Composable (() -> Unit) -> Unit,
    lastBackupTime: Long = 0L,
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Shake sensitivity
    shakeSensitivity: Float = 0.5f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // GROQ key usage stats
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    // Voice fingerprint management
    isVoiceEnrolled: Boolean = false,
    onDeleteVoiceFingerprint: () -> Unit = {},
    onRetrainVoice: () -> Unit = {},
    // TTS for AI responses
    isTTSEnabled: Boolean = true,
    onTTSEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { AIModels.getModelsForProvider(it) },
    onSignOut: () -> Unit = {}
) {
    var showRemovePinDialog by remember { mutableStateOf(false) }
    var showDeleteVoiceFingerprintDialog by remember { mutableStateOf(false) }
    var showAIConfigSheet by remember { mutableStateOf(false) }
    val aiConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Bottom Sheets for Sub-settings
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showPinSetupSheet by remember { mutableStateOf(false) }
    var showPinChangeSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }  // Newly added state
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showVoiceFingerprintSheet by remember { mutableStateOf(false) }
    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local PC Testing state (FOR TESTING ONLY - Remove before publishing!)
    var localPCTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingLocalPC by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSystemDark = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onToggleTheme(!isDarkTheme) }) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {


            // Main Settings List
            // Main Settings List
            SettingsItem(
                icon = Icons.Default.Psychology,
                title = "AI Providers",
                subtitle = "Configure models & keys",
                onClick = { showAIConfigSheet = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                icon = Icons.Default.CloudSync,
                title = "Backup & Sync",
                subtitle = if (lastBackupTime > 0) {
                    val sdf = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                    "Last: ${sdf.format(java.util.Date(lastBackupTime))}"
                } else "Not backed up",
                onClick = { showBackupSheet = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                icon = Icons.Default.Archive,
                title = "Archive",
                subtitle = "View archived notes",
                onClick = { showArchiveSheet = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Security & Storage
            if (isPinConfigured) {
                SettingsItem(
                    icon = Icons.Default.Password,
                    title = "Change PIN",
                    subtitle = "Update your security PIN",
                    onClick = { showPinChangeSheet = true }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsItem(
                    icon = Icons.Default.LockOpen,
                    title = "Remove PIN",
                    subtitle = "Disable PIN protection",
                    onClick = { showRemovePinDialog = true },
                    isDestructive = true,
                    iconColor = SafetyOrange
                )
            } else {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = "Set Up PIN",
                    subtitle = "Protect your notes",
                    onClick = { showPinSetupSheet = true }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Shake Sensitivity
            SettingsItem(
                icon = Icons.Default.Vibration,
                title = "Shake Sensitivity",
                subtitle = "${(shakeSensitivity * 100).toInt()}% - ${
                    when {
                        shakeSensitivity < 0.3f -> "Low"
                        shakeSensitivity < 0.7f -> "Medium"
                        else -> "High"
                    }
                }",
                onClick = { showShakeSensitivitySheet = true },
                showArrow = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Voice Fingerprint Section - Consolidated
            SettingsItem(
                icon = Icons.Default.RecordVoiceOver,
                title = if (isVoiceEnrolled) "Voice ID" else "Set Up Voice ID",
                subtitle = if (isVoiceEnrolled) "Active • Tap to manage" else "Enable voice-only wake word",
                onClick = {
                    if (isVoiceEnrolled) {
                        showVoiceFingerprintSheet = true
                    } else {
                        onRetrainVoice()
                    }
                },
                showArrow = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // TTS for AI Responses Toggle
            SettingsToggleItem(
                icon = Icons.Filled.VolumeUp,
                title = "Speak AI Responses",
                subtitle = if (isTTSEnabled) "AI replies are spoken aloud" else "Text-only responses",
                isChecked = isTTSEnabled,
                onCheckedChange = onTTSEnabledChange
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                icon = Icons.Default.CleaningServices,
                title = "Clear Cache",
                subtitle = formatCacheSize(cacheSizeBytes),
                onClick = onClearCache,
                isLoading = isClearingCache,
                enabled = !isClearingCache && cacheSizeBytes > 0,
                iconColor = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Account & Sign Out
            SettingsItem(
                icon = Icons.Default.Logout,
                title = "Sign Out",
                subtitle = "Log out of your account",
                onClick = onSignOut,
                isDestructive = true,
                iconColor = SafetyOrange
            )

            Spacer(modifier = Modifier.height(12.dp))

            // API Info & Metrics Section
            val metrics by ApiMetrics.metricsFlow.collectAsState()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp)),
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(LocalAccentColor.current.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.QueryStats,
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Text(
                            text = "API Info & Metrics",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Success Rate
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total API Calls", style = MaterialTheme.typography.bodyMedium)
                        Text("${metrics.totalCalls}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Successful", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF4CAF50))
                        Text("${metrics.successfulCalls}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Failed", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF44336))
                        Text("${metrics.failedCalls}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Success Rate Bar
                    val successRate = metrics.successRate
                    Text(
                        "Success Rate: ${(successRate * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { successRate },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF4CAF50),
                        trackColor = Color(0xFFF44336).copy(alpha = 0.3f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Cache Stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cache Hits", style = MaterialTheme.typography.bodyMedium)
                        Text("${metrics.cacheHits}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Cache Misses", style = MaterialTheme.typography.bodyMedium)
                        Text("${metrics.cacheMisses}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    // Cache Hit Rate Bar
                    val cacheHitRate = metrics.cacheHitRate
                    Text(
                        "Cache Hit Rate: ${(cacheHitRate * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = { cacheHitRate },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Last Reset Time
                    val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
                    Text(
                        "Stats since: ${dateFormat.format(Date(metrics.lastResetTime))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Reset Button
                    OutlinedButton(
                        onClick = { ApiMetrics.reset() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Statistics")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Local PC Testing Section (FOR TESTING ONLY - Remove before publishing!)
            val localPCEnabled = providerConfigs[AIProvider.LOCAL_PC]?.isEnabled ?: true
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (localPCEnabled)
                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = if (localPCEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local PC (USB Tethering)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (localPCEnabled) "Enabled - Uses local AI server" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Enable/Disable Switch
                        Switch(
                            checked = localPCEnabled,
                            onCheckedChange = { onSetProviderEnabled(AIProvider.LOCAL_PC, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.tertiary,
                                checkedTrackColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    }

                    if (localPCEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))

                        // Connection status
                        localPCTestStatus?.let { status ->
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (status.contains("Success") || status.contains("online"))
                                    Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        // Test button
                        Button(
                            onClick = {
                                isTestingLocalPC = true
                                localPCTestStatus = "Testing connection..."
                                coroutineScope.launch {
                                    try {
                                        val status = withContext(Dispatchers.IO) {
                                            val client = OkHttpClient.Builder()
                                                .connectTimeout(10, TimeUnit.SECONDS)
                                                .readTimeout(10, TimeUnit.SECONDS)
                                                .build()
                                            val request = Request.Builder()
                                                .url("http://10.224.189.60:8000/v1/models")
                                                .get()
                                                .build()
                                            val response = client.newCall(request).execute()
                                            if (response.isSuccessful) {
                                                "Server online - Ready"
                                            } else {
                                                "Server responded: ${response.code}"
                                            }
                                        }
                                        localPCTestStatus = status
                                    } catch (e: Exception) {
                                        localPCTestStatus = "Connection failed: ${e.message}"
                                    } finally {
                                        isTestingLocalPC = false
                                    }
                                }
                            },
                            enabled = !isTestingLocalPC,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary
                            )
                        ) {
                            if (isTestingLocalPC) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onTertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(if (isTestingLocalPC) "Testing..." else "Test Connection")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Server: http://10.224.189.60:8000",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // About Section
            SettingsItem(
                icon = Icons.Default.Info,
                title = "About Loum",
                subtitle = "Version 1.1.0",
                onClick = { showAboutSheet = true },
                showArrow = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Footer
            Text(
                text = "Made with intelligence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // AI Configuration Bottom Sheet
    if (showAIConfigSheet) {
        AIConfigBottomSheet(
            sheetState = aiConfigSheetState,
            providerConfigs = providerConfigs,
            providerPriorityOrder = providerPriorityOrder,
            onDismiss = { showAIConfigSheet = false },
            onAddApiKey = onAddApiKey,
            onRemoveApiKey = onRemoveApiKey,
            onUpdateApiKey = onUpdateApiKey,
            onSetProviderEnabled = onSetProviderEnabled,
            onSetSelectedModel = onSetSelectedModel,
            onSetProviderPriority = onSetProviderPriority,
            onTestApiKey = onTestApiKey,
            tavilyApiKey = tavilyApiKey,
            onSetTavilyApiKey = onSetTavilyApiKey,
            groqKeyUsageStats = groqKeyUsageStats,
            onRefreshModels = onRefreshModels,
            getAvailableModels = getAvailableModels
        )
    }

    // Archive Bottom Sheet
    if (showArchiveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showArchiveSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f) // Restrict height
                    .fillMaxWidth()
                    .padding(bottom = 32.dp) // Nav bar padding
            ) {
                archiveContent { showArchiveSheet = false }
            }
        }
    }

    // Backup Bottom Sheet
    if (showBackupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBackupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                 Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
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

    // Pin Setup Bottom Sheet
    if (showPinSetupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPinSetupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
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

    // Pin Change Bottom Sheet
    if (showPinChangeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPinChangeSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
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
            shape = LocalShapes.current.cardMedium
        )
    }

    // Delete Voice Fingerprint Dialog
    if (showDeleteVoiceFingerprintDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteVoiceFingerprintDialog = false },
            title = { Text("Delete Voice Fingerprint?") },
            text = { Text("Your voice ID will be removed. Anyone's voice will be able to trigger the wake word.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteVoiceFingerprint()
                    showDeleteVoiceFingerprintDialog = false
                    showVoiceFingerprintSheet = false
                }) {
                    Text("Delete", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteVoiceFingerprintDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = LocalShapes.current.cardMedium
        )
    }

    // Voice Fingerprint Management Bottom Sheet
    if (showVoiceFingerprintSheet) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceFingerprintSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                // Header
                Text(
                    text = "Voice ID",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Your voice fingerprint is active. Only your voice will trigger the wake word.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Status indicator
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(LocalAccentColor.current.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = LocalAccentColor.current,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        Column {
                            Text(
                                text = "Voice ID Active",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Your unique voice pattern is stored",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Retrain Button
                Button(
                    onClick = {
                        showVoiceFingerprintSheet = false
                        onRetrainVoice()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Retrain Voice ID",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        )
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Delete Button
                OutlinedButton(
                    onClick = { showDeleteVoiceFingerprintDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    border = BorderStroke(1.dp, SafetyOrange.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = SafetyOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Delete Voice ID",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        color = SafetyOrange
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Info text
                Text(
                    text = "Retraining will replace your current voice fingerprint with a new one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }

    // About Bottom Sheet
    if (showAboutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAboutSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.85f)
                    .fillMaxWidth()
            ) {
                // Fixed Header
                Text(
                    text = "About Loum",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp)
                )

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text =  "Hello, I am Moudook.\n\n" +
                                "Loum is an AI-powered personal knowledge management app. I made this mainly for myself so I do not get fussed managing my notes and content. The idea is simple. Capture anything, and let the AI help later with organizing, searching, and recalling things when needed.\n\n" +
                                "You can add many types of content. Text notes for brain dumps. Images. Videos, both YouTube and local. Documents like PDF, DOCX, XLSX, and PPTX. Website links with metadata. Audio files and voice notes. Code snippets. Twitter or X posts, Instagram posts, APK files, and archive files.\n\n" +
                                "For organization, I added basic but useful things. You can pin notes so important ones stay on top. Notes get smart categories automatically. AI also generates tags for every note. If you do not want something but also do not want to delete it, you can archive it. There is also bulk selection so you can operate on many notes at once.\n\n" +
                                "I also added note versioning. Every note has a Git-like history. Up to 10 versions are saved automatically. You can restore any older version instantly. You can also see what changed and when it changed.\n\n" +
                                "Search was one of the main focus areas.\n\n" +
                                "I added full-text search using FTS5. It searches across all notes instantly. It uses BM25 ranking so relevant notes come first. It searches titles, content, and summaries. It also supports prefix matching for partial words.\n\n" +
                                "Along with that, I added semantic search. It can handle typos. It uses fuzzy matching, Jaro-Winkler similarity, phonetic matching using Soundex, and n-gram token overlap so search still works even if you do not type things perfectly.\n\n" +
                                "The AI assistant is agentic.\n\n" +
                                "It can search and retrieve notes based on context. It can create new notes directly from conversation. It can edit and update existing notes. It can delete notes when asked. It can manage to-do lists inside notes. It can also set smart reminders on cards.\n\n" +
                                "Every AI response shows citations. You can tap a citation and jump directly to the source note. So you always know where the information is coming from.\n\n" +
                                "I also added smart reminders. AI can highlight important notes. A shimmer animation is used to draw attention. You can also set expiration on reminders so temporary things do not stay forever.\n\n" +
                                "For the agentic AI, I use KOOG by JetBrains. KOOG stands for Kotlin Object-Oriented Graphs. It lets the AI work with structured tool calls and function execution. The AI can decide which tools to use and chain them together. This is what makes the assistant truly agentic instead of just a chatbot. KOOG handles the execution graph and makes sure everything runs in the right order.\n\n" +
                                "Multiple AI providers are supported. Google Gemini is the default. I also added support for OpenAI, Anthropic, Groq, DeepSeek, Cerebras, Cohere, OpenRouter, and HuggingFace. You can manage multiple API keys per provider. You can reorder provider priority by drag and drop. Automatic key rotation and rate limit handling are also there. You can choose models per provider.\n\n" +
                                "For web search, I integrated Tavily. It gives real-time information. There are 1000 free requests per month. AI summarizes the results so you do not need to read everything manually.\n\n" +
                                "I also added a daily digest. It sends a notification at 6:30 AM. It summarizes what happened in the last 24 hours. New notes and important updates show up there.\n\n" +
                                "Voice and audio were also important.\n\n" +
                                "You can record voice notes instantly by tapping the mic. There is a real-time amplitude visualizer. Audio is saved in M4A format. You can record up to 10 minutes. You always get a confirmation before saving or canceling.\n\n" +
                                "Speech-to-text uses Google Speech Recognition. A halftone shimmer shows when it is actively listening. Continuous recognition mode is also supported.\n\n" +
                                "I also added wake word detection using Vosk. It runs on device. It works without internet. There is also a high sensitivity mode.\n\n" +
                                "The music player has a living orb visualizer. It reacts to audio amplitude and frequency bands. There is a mini player and a full-screen mode. I tried to make it feel alive.\n\n" +
                                "Calendar support is also there.\n\n" +
                                "You can import events from Google Calendar. Exchange and other providers are supported. It syncs past 30 days and next 90 days. Events update automatically.\n\n" +
                                "AI can access your calendar, but only non-private events. It can create events from conversation. You can also link notes to calendar events. Private events stay hidden from AI.\n\n" +
                                "Privacy was non-negotiable.\n\n" +
                                "There are multiple privacy modes. Full privacy means AI cannot see the note at all. Exclude from chat means the note stays hidden from AI context. Private calendar events are also invisible to AI.\n\n" +
                                "I added shake-to-private mode. You can shake the device to toggle privacy. Sensitivity can be adjusted. There is visual feedback so you know when the mode changes.\n\n" +
                                "The app supports PIN protection. You can use a 4-digit PIN or biometrics. You can change or remove it anytime.\n\n" +
                                "I also added prompt injection protection. Content is sanitized across multiple languages. This runs on device. It prevents malicious note content from affecting the AI.\n\n" +
                                "All data is stored locally on the device. I use a Room database for persistence. API keys are stored using Android's EncryptedSharedPreferences. Only API calls go out of the device.\n\n" +
                                "For user experience, I added widgets. You can capture notes directly from the home screen. One tap and you are inside a new note.\n\n" +
                                "App shortcuts are also there. Long press the app icon for quick actions like new note, search, or voice note. Recent notes also show up dynamically.\n\n" +
                                "Sharing is deeply integrated. You can share content from any app into Loum. Content type is detected automatically. URL metadata is extracted. You can also bulk select and share multiple notes.\n\n" +
                                "For UI, I avoided popups. I use slide-in panels instead. Animations are smooth and spring-based. Entry animations are staggered. Dark and light themes are supported. You can also customize accent colors.\n\n" +
                                "There are many animations. Cloud-like startup animation. Living orb on the main screen. Chat personality animations. Shimmer effects for reminders. Halftone indicator for speech.\n\n" +
                                "Performance was a big focus.\n\n" +
                                "I optimized memory usage using shared HTTP clients. Resources are cleaned automatically. Coroutines are scoped properly so there are no leaks. Cache size is managed.\n\n" +
                                "CPU usage is optimized by merging animation transitions, using derivedStateOf where needed, pre-compiling regex patterns, early terminating searches, and using fast math approximations.\n\n" +
                                "The database uses Room with SQLite. Queries are indexed. FTS5 is used for search. Migrations are automatic. Paging3 is used for infinite scrolling.\n\n" +
                                "The app is built to work on edge and low-end devices. Rendering quality adapts. Memory usage stays low. Background work is battery conscious.\n\n" +
                                "Backup is simple.\n\n" +
                                "You can export a full ZIP backup. It contains notes, categories, settings, chat history, AI memories, calendar events, and attachments. You can restore anytime. You can choose to merge or replace data. Integrity is preserved.\n\n" +
                                "The app is built using Kotlin and Jetpack Compose. It follows Material Design 3. Room Database version is 19. Min SDK is 26. Target SDK is 36. WorkManager is used for background tasks. OkHttp handles networking. Vosk is used for offline speech. ExoPlayer handles media.\n\n" +
                                "Your data stays on your device. Only AI and speech recognition APIs need internet.\n\n" +
                                "I am still working on the UI and the agentic part. If you find any issues, please mention them on GitHub.\n\n" +
                                "Thank you for using Loum.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Shake Sensitivity Bottom Sheet
    if (showShakeSensitivitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showShakeSensitivitySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
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
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Adjust how sensitive the shake gesture is for toggling private mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Semicircle sensitivity control
                ShakeSensitivityControl(
                    sensitivity = shakeSensitivity,
                    onSensitivityChange = onShakeSensitivityChange,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hint text
                Text(
                    text = when {
                        shakeSensitivity < 0.3f -> "Low: Requires strong shake movement"
                        shakeSensitivity < 0.7f -> "Medium: Balanced sensitivity"
                        else -> "High: Light shake triggers mode switch"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}



// Redundant SettingsGroup removed

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = LocalAccentColor.current,
    isDestructive: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    showArrow: Boolean = true
) {
    // Matching CategoryCard style
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                 modifier = Modifier
                     .size(40.dp)
                     .clip(CircleShape)
                     .background(iconColor.copy(alpha = 0.15f)),
                 contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = if (isDestructive) SafetyOrange else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Action
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = iconColor
                )
            } else if (showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * Settings item with a toggle switch instead of arrow
 */
@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = LocalAccentColor.current,
    enabled: Boolean = true
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Toggle Switch
            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = iconColor,
                    checkedTrackColor = iconColor.copy(alpha = 0.3f)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIConfigBottomSheet(
    sheetState: SheetState,
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    onDismiss: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    tavilyApiKey: String? = null,
    onSetTavilyApiKey: (String?) -> Unit = {},
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>
) {
    val shapes = LocalShapes.current

    // Local state for drag-and-drop reordering
    // Filter out LOCAL_PC - it has its own section and doesn't need API keys
    var localProviderOrder by remember {
        mutableStateOf(providerPriorityOrder.filter { it != AIProvider.LOCAL_PC })
    }
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // Sync with external state when it changes
    LaunchedEffect(providerPriorityOrder) {
        localProviderOrder = providerPriorityOrder.filter { it != AIProvider.LOCAL_PC }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "AI Providers",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Hold and drag providers to reorder fallback priority.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val providerInfo = mapOf(
                AIProvider.GEMINI to Triple("Gemini", "Google's fastest AI", "https://aistudio.google.com/apikey"),
                AIProvider.DEEPSEEK to Triple("DeepSeek", "Cost-effective", "https://platform.deepseek.com"),
                AIProvider.GROQ to Triple("Groq", "Ultra-fast", "https://console.groq.com"),
                AIProvider.CEREBRAS to Triple("Cerebras", "2000+ tokens/sec", "https://cloud.cerebras.ai"),
                AIProvider.COHERE to Triple("Cohere", "Command models", "https://dashboard.cohere.com/api-keys"),
                AIProvider.OPENAI to Triple("OpenAI", "GPT-4o", "https://platform.openai.com/api-keys"),
                AIProvider.ANTHROPIC to Triple("Anthropic", "Claude models", "https://console.anthropic.com/settings/keys"),
                AIProvider.OPENROUTER to Triple("OpenRouter", "Multi-model", "https://openrouter.ai/keys"),
                AIProvider.HUGGINGFACE to Triple("HuggingFace", "Open source", "https://huggingface.co/settings/tokens"),
                AIProvider.GITHUB to Triple("GitHub Models", "Free with GitHub", "https://github.com/settings/tokens"),
                AIProvider.LOCAL_PC to Triple("Local PC (USB)", "Offline testing", "")  // FOR TESTING ONLY
            )

            // Iterate through providers with drag-and-drop reordering
            localProviderOrder.forEachIndexed { index, provider ->
                val (name, description, _) = providerInfo[provider] ?: Triple("Unknown", "", "")
                val config = providerConfigs[provider]
                val isDragging = draggedItemIndex == index

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetY
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(
                            if (isDragging) Modifier.shadow(8.dp, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    // Drag Handle with gesture detection
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = index
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        // Calculate target position based on drag offset
                                        val itemHeight = 120f // Approximate item height in pixels
                                        val moveBy = (dragOffsetY / itemHeight).toInt()
                                        val targetIndex = (index + moveBy).coerceIn(0, localProviderOrder.size - 1)

                                        if (targetIndex != index) {
                                            val newList = localProviderOrder.toMutableList()
                                            val item = newList.removeAt(index)
                                            newList.add(targetIndex, item)
                                            localProviderOrder = newList
                                            onSetProviderPriority(newList)
                                        }

                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Drag indicator icon
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = if (isDragging) LocalAccentColor.current
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            // Priority number badge
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDragging) LocalAccentColor.current
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        // Build usage stats map for GROQ keys
                        val keyUsageStatsMap = if (provider == AIProvider.GROQ) {
                            groqKeyUsageStats.associateBy { it.key }
                        } else {
                            emptyMap()
                        }

                        ProviderSection(
                            provider = provider,
                            providerName = name,
                            providerDescription = description,
                            apiKeys = config?.apiKeys ?: emptyList(),
                            isEnabled = config?.isEnabled ?: true,
                            selectedModel = config?.selectedModel ?: AIModels.getDefaultModel(provider),
                            availableModels = getAvailableModels(provider),
                            onAddKey = { onAddApiKey(provider, it) },
                            onRemoveKey = { onRemoveApiKey(provider, it) },
                            onUpdateKey = { old, new -> onUpdateApiKey(provider, old, new) },
                            onToggleEnabled = { onSetProviderEnabled(provider, it) },
                            onSelectModel = { onSetSelectedModel(provider, it) },
                            onTestKey = { key, callback -> onTestApiKey(provider, key, callback) },
                            keyUsageStats = keyUsageStatsMap,
                            onRefreshModels = if (provider == AIProvider.GROQ) { { onRefreshModels(provider) } } else null
                        )
                    }
                }

                if (index < localProviderOrder.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tavily Web Search Section
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            TavilyApiSection(
                apiKey = tavilyApiKey,
                onSetApiKey = onSetTavilyApiKey
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Status
            val hasAnyKeys = providerConfigs.values.any { it.apiKeys.isNotEmpty() }
            val configuredCount = providerConfigs.values.count { it.apiKeys.isNotEmpty() }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = if (hasAnyKeys) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasAnyKeys) "$configuredCount provider${if (configuredCount > 1) "s" else ""} configured" else "No API keys - using demo mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tavily Web Search API configuration section.
 * Separate from AI providers as it's a search tool, not a chat model.
 */
@Composable
private fun TavilyApiSection(
    apiKey: String?,
    onSetApiKey: (String?) -> Unit
) {
    var showKeyInput by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showKeyWhileTyping by remember { mutableStateOf(false) }  // For masking input
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )

                Column {
                    Text(
                        text = "Tavily Web Search",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (apiKey != null) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Enable AI web search capabilities",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status indicator
            if (apiKey != null) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Configured",
                    tint = LocalAccentColor.current,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Expanded content
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Info text
                Text(
                    text = "Tavily provides real-time web search for AI. Get your free API key (1,000 requests/month) from tavily.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                if (apiKey != null) {
                    // Show existing key
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                LocalAccentColor.current,
                                RoundedCornerShape(ComponentSpacing.inputCornerRadius)
                            ),
                        shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showKey) apiKey else maskApiKey(apiKey),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = com.example.smarty.ui.theme.MonoFont
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showKey) "Hide" else "Show",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(onClick = { onSetApiKey(null) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = SafetyOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else if (showKeyInput) {
                    // Input for new key
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                LocalAccentColor.current,
                                RoundedCornerShape(ComponentSpacing.inputCornerRadius)
                            ),
                        shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = keyInput,
                                        onValueChange = { keyInput = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = com.example.smarty.ui.theme.MonoFont,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                                        singleLine = true,
                                        // Mask the key while typing for security
                                        visualTransformation = if (showKeyWhileTyping) {
                                            androidx.compose.ui.text.input.VisualTransformation.None
                                        } else {
                                            androidx.compose.ui.text.input.PasswordVisualTransformation()
                                        }
                                    )
                                    if (keyInput.isEmpty()) {
                                        Text(
                                            text = "tvly-XXXXX...",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = com.example.smarty.ui.theme.MonoFont
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                                // Toggle to show/hide key while typing
                                IconButton(
                                    onClick = { showKeyWhileTyping = !showKeyWhileTyping },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (showKeyWhileTyping) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showKeyWhileTyping) "Hide key" else "Show key",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    showKeyInput = false
                                    keyInput = ""
                                }) {
                                    Text("Cancel")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (keyInput.isNotBlank()) {
                                            onSetApiKey(keyInput.trim())
                                            keyInput = ""
                                            showKeyInput = false
                                        }
                                    },
                                    enabled = keyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LocalAccentColor.current,
                                        contentColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                } else {
                    // Add key button
                    OutlinedButton(
                        onClick = { showKeyInput = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(ComponentSpacing.iconSize)
                        )
                        Spacer(modifier = Modifier.width(ComponentSpacing.iconGap))
                        Text("Add Tavily API Key")
                    }
                }
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
