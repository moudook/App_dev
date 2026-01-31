package com.example.smarty.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.NoAccounts
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import com.example.smarty.ui.components.ConnectionStatusIndicator
import com.example.smarty.ui.components.ConnectionStatus
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
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
import com.example.smarty.ui.screens.settings.DataManagementSection
import com.example.smarty.ui.screens.settings.formatCacheSize
import com.example.smarty.util.api.ApiMetrics
import com.example.smarty.util.api.KeyUsageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.smarty.ui.components.CalmThinkingDots
import com.example.smarty.ui.components.ShakeSensitivityControl
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SystemGreen
import androidx.compose.ui.platform.LocalContext
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
    isDarkTheme: Boolean,
    onBackClick: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    // Tavily Web Search API
    tavilyApiKeys: List<String> = emptyList(),
    onAddTavilyApiKey: (String) -> Unit = {},
    onRemoveTavilyApiKey: (String) -> Unit = {},
    // Embedded Content Slots
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    lastBackupTime: Long = 0L,
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Google Calendar Sync
    lastCalendarSyncTime: Long = 0L,
    onCalendarSync: () -> Unit = {},
    // Shake sensitivity
    shakeSensitivity: Float = 0.5f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // GROQ key usage stats
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    // Local LLM Server (USB/WiFi)
    isLocalPCEnabled: Boolean = false,
    onSetLocalPCEnabled: (Boolean) -> Unit = {},
    localServerIP: String = "",
    localServerPort: String = "1234",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { AIModels.getModelsForProvider(it) },
    onSignOut: () -> Unit = {},
    // AI Memory
    aiMemories: List<com.example.smarty.data.model.AIMemory> = emptyList(),
    onDeleteAIMemory: (com.example.smarty.data.model.AIMemory) -> Unit = {},
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
    var showAIConfigSheet by remember { mutableStateOf(false) }
    val aiConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Bottom Sheets for Sub-settings
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }  // Newly added state
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showAIMemorySheet by remember { mutableStateOf(false) }
    var showCalendarSelectorSheet by remember { mutableStateOf(false) }
    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Local LLM connection state
    var localPCTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingLocalPC by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSystemDark = isSystemInDarkTheme()

    // Intercept system back button
    // If any sheet is open, close it. Otherwise, navigate back.
    androidx.activity.compose.BackHandler(onBack = {
        if (showArchiveSheet || showBackupSheet || showAboutSheet || showShakeSensitivitySheet || showAIConfigSheet || showAIMemorySheet || showCalendarSelectorSheet) {
            showArchiveSheet = false
            showBackupSheet = false
            showAboutSheet = false
            showShakeSensitivitySheet = false
            showAIConfigSheet = false
            showAIMemorySheet = false
            showCalendarSelectorSheet = false
        } else {
            onBackClick()
        }
    })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        // Grouped section expand states
        var expandedSection by remember { mutableStateOf<String?>("ai") } // AI section open by default

        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 1: AI & VOICE
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.ai_voice),
                icon = Icons.Default.Assistant,
                isExpanded = expandedSection == "ai",
                onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }
            ) {
                SettingsRow(
                    title = stringResource(R.string.ai_providers),
                    icon = Icons.Default.Assistant,
                    subtitle = stringResource(R.string.models_and_api_keys),
                    onClick = { showAIConfigSheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.ai_memory),
                    icon = Icons.Default.Assistant,
                    subtitle = if (aiMemories.isEmpty()) stringResource(R.string.no_memories) else "${aiMemories.size} ${stringResource(R.string.memories)}",
                    onClick = { showAIMemorySheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 2: CALENDAR INTEGRATION
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.calendar),
                icon = Icons.Default.Event,
                isExpanded = expandedSection == "calendar",
                onToggle = {
                    expandedSection = if (expandedSection == "calendar") null else "calendar"
                    if (expandedSection == "calendar") onLoadDeviceCalendars()
                }
            ) {
                SettingsToggleRow(
                    title = stringResource(R.string.sync_to_google_calendar),
                    icon = Icons.Default.Sync,
                    isChecked = isCalendarSyncEnabled,
                    onCheckedChange = onSetCalendarSyncEnabled,
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )

                if (isCalendarSyncEnabled) {
                    val selectedCalendar = deviceCalendars.find { it.id == targetCalendarId }
                    SettingsRow(
                        title = stringResource(R.string.default_calendar),
                        icon = Icons.Default.Event,
                        subtitle = selectedCalendar?.displayName?.lowercase() ?: stringResource(R.string.select_calendar),
                        onClick = { showCalendarSelectorSheet = true },
                        iconColor = LocalAccentColor.current,
                        containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 3: DATA
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.data_vault),
                icon = Icons.Default.Storage,
                isExpanded = expandedSection == "data",
                onToggle = { expandedSection = if (expandedSection == "data") null else "data" }
            ) {
                SettingsRow(
                    title = stringResource(R.string.backup_sync),
                    icon = Icons.Default.CloudSync,
                    subtitle = if (lastBackupTime > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                        "${stringResource(R.string.last_)} ${sdf.format(java.util.Date(lastBackupTime))}"
                    } else stringResource(R.string.not_backed_up),
                    onClick = { showBackupSheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.google_calendar_sync),
                    icon = Icons.Default.Sync,
                    subtitle = if (lastCalendarSyncTime > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                        "${stringResource(R.string.last_sync_)} ${sdf.format(java.util.Date(lastCalendarSyncTime))}"
                    } else stringResource(R.string.not_synced),
                    onClick = onCalendarSync,
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.archive),
                    icon = Icons.Default.Archive,
                    subtitle = stringResource(R.string.view_archived_notes),
                    onClick = { showArchiveSheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                SettingsRow(
                    title = stringResource(R.string.clear_cache),
                    icon = Icons.Filled.DeleteOutline,
                    subtitle = formatCacheSize(cacheSizeBytes),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0,
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 4: PREFERENCES
            // ═══════════════════════════════════════════════════════════════════
            SettingsSection(
                title = stringResource(R.string.preferences),
                icon = Icons.Default.Settings,
                isExpanded = expandedSection == "prefs",
                onToggle = { expandedSection = if (expandedSection == "prefs") null else "prefs" }
            ) {
                SettingsRow(
                    title = stringResource(R.string.shake_sensitivity),
                    icon = Icons.Filled.Waves,
                    subtitle = when {
                        shakeSensitivity < 0.3f -> stringResource(R.string.low)
                        shakeSensitivity < 0.7f -> stringResource(R.string.medium)
                        else -> stringResource(R.string.high)
                    },
                    onClick = { showShakeSensitivitySheet = true },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
                // Assistant Settings
                val context = LocalContext.current
                SettingsRow(
                    title = stringResource(R.string.default_assistant),
                    icon = Icons.Default.Build,
                    subtitle = stringResource(R.string.set_as_device_assistant),
                    onClick = {
                        try {
                            // Open Android's assistant settings
                            val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to general settings if voice input settings not available
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (e2: Exception) {
                                android.util.Log.e("Settings", "Could not open settings: ${e2.message}")
                            }
                        }
                    },
                    iconColor = LocalAccentColor.current,
                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                )
            }

            // ═══════════════════════════════════════════════════════════════════
            // SECTION 5: ACCOUNT (No expand - direct actions)
            // ═══════════════════════════════════════════════════════════════════
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                onClick = onSignOut
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.NoAccounts,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.sign_out),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // FOOTER
            // ═══════════════════════════════════════════════════════════════════
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.smarty_version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.made_with_intelligence),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }

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
            tavilyApiKeys = tavilyApiKeys,
            onAddTavilyApiKey = onAddTavilyApiKey,
            onRemoveTavilyApiKey = onRemoveTavilyApiKey,
            groqKeyUsageStats = groqKeyUsageStats,
            onRefreshModels = onRefreshModels,
            getAvailableModels = getAvailableModels,
            isLocalPCEnabled = isLocalPCEnabled,
            onSetLocalPCEnabled = onSetLocalPCEnabled,
            localServerIP = localServerIP,
            localServerPort = localServerPort,
            localServerUseHttps = localServerUseHttps,
            onSetLocalServerIP = onSetLocalServerIP,
            onSetLocalServerPort = onSetLocalServerPort,
            onSetLocalServerUseHttps = onSetLocalServerUseHttps
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

    // Note: Delete Voice Fingerprint Dialog is now handled inside VoiceFingerprintSheetContent


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
                    text = stringResource(R.string.about_smarty),
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
                        text = stringResource(R.string.about_description),
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
        val accentColor = LocalAccentColor.current
        
        ModalBottomSheet(
            onDismissRequest = { showShakeSensitivitySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.background,
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
                // Modern Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.shake_gesture),
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.configure_chat_mode_activation),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                        )
                    }
                    
                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.1f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Sensors,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Main Card with Control - matching notecard aesthetic
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
                        ShakeSensitivityControl(
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
                            text = stringResource(R.string.requires_stronger_shake),
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
                            text = stringResource(R.string.light_shake_triggers),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }

    // AI Memory Bottom Sheet
    if (showAIMemorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showAIMemorySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.background,
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
                    .fillMaxHeight(0.7f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                com.example.smarty.ui.screens.settings.AIMemorySettingsContent(
                    memories = aiMemories,
                    onDeleteMemory = onDeleteAIMemory,
                    onClearAllMemories = onClearAllAIMemories,
                    onDismiss = { showAIMemorySheet = false },
                    // Sync functionality
                    onSyncMemories = onSyncAIMemories,
                    isSyncing = isMemorySyncInProgress,
                    syncResult = memorySyncResult,
                    unreadNotesCount = unreadForMemoryCount,
                    onClearSyncResult = onClearMemorySyncResult
                )
            }
        }
    }

    // Calendar Selector Bottom Sheet
    if (showCalendarSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCalendarSelectorSheet = false },
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
                            color = if (isSelected) LocalAccentColor.current.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, LocalAccentColor.current) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(calendar.color?.let { Color(it) } ?: Color.Gray)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = calendar.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isSelected) LocalAccentColor.current else MaterialTheme.colorScheme.onSurface
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
                                        tint = LocalAccentColor.current,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (deviceCalendars.isEmpty()) {
                    com.example.smarty.ui.components.CompactEmptyState(
                        title = stringResource(R.string.calendars),
                        subtitle = stringResource(R.string.no_calendars_found_or_permission_denied),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Action
            if (isLoading) {
                CalmThinkingDots(
                    dotSize = 3.dp
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
internal fun AIConfigBottomSheet(
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
    tavilyApiKeys: List<String> = emptyList(),
    onAddTavilyApiKey: (String) -> Unit = {},
    onRemoveTavilyApiKey: (String) -> Unit = {},
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>,
    // Local LLM
    isLocalPCEnabled: Boolean = false,
    onSetLocalPCEnabled: (Boolean) -> Unit = {},
    localServerIP: String = "",
    localServerPort: String = "1234",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {}
) {
    val shapes = LocalShapes.current

    // Local state for drag-and-drop reordering
    // Include all providers including LOCAL_PC for consistent UI treatment
    var localProviderOrder by remember {
        mutableStateOf(providerPriorityOrder)
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
        containerColor = MaterialTheme.colorScheme.surface, // Use surface for cleaner look
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.3f)
    ) {
        // Custom Drag Handle for cleaner look
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxHeight(0.92f)
                .fillMaxWidth()
        ) {
            // Header - Centralized and Minimal
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = LocalAccentColor.current.copy(alpha = 0.1f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Assistant,
                            contentDescription = null,
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.ai_intelligence),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.configure_ai_providers_and_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp) // Consistent padding
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Drag Hint
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DragIndicator,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.drag_providers_to_reorder_priority),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                val providerInfo = mapOf(
                    AIProvider.GEMINI to Triple("gemini", "google's_fastest_ai", "https://aistudio.google.com/apikey"),
                    AIProvider.DEEPSEEK to Triple("deepseek", "cost-effective", "https://platform.deepseek.com"),
                    AIProvider.GROQ to Triple("groq", "ultra-fast", "https://console.groq.com"),
                    AIProvider.CEREBRAS to Triple("cerebras", "2000+_tokens/sec", "https://cloud.cerebras.ai"),
                    AIProvider.COHERE to Triple("cohere", "command_models", "https://dashboard.cohere.com/api-keys"),
                    AIProvider.OPENAI to Triple("openai", "gpt-4o", "https://platform.openai.com/api-keys"),
                    AIProvider.ANTHROPIC to Triple("anthropic", "claude_models", "https://console.anthropic.com/settings/keys"),
                    AIProvider.OPENROUTER to Triple("openrouter", "multi-model", "https://openrouter.ai/keys"),
                    AIProvider.HUGGINGFACE to Triple("huggingface", "open_source", "https://huggingface.co/settings/tokens"),
                    AIProvider.GITHUB to Triple("github_models", "free_with_github", "https://github.com/settings/tokens"),
                    AIProvider.LOCAL_PC to Triple("local_llm", "run_ai_locally", "")
                )

            // Iterate through providers with drag-and-drop reordering
            localProviderOrder.forEachIndexed { index, provider ->
                val (name, description, _) = providerInfo[provider] ?: Triple("unknown", "", "")
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
                                shadowElevation = 8.dp.toPx()
                            }
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    // Drag Handle
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = index
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        val itemHeight = 100f // Approx height
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
                        Icon(
                            imageVector = Icons.Default.DragHandle,
                            contentDescription = stringResource(R.string.drag_to_reorder),
                            tint = if (isDragging) LocalAccentColor.current
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
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
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Other Sections
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                TavilyApiSection(
                    apiKeys = tavilyApiKeys,
                    onAddKey = onAddTavilyApiKey,
                    onRemoveKey = onRemoveTavilyApiKey
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status Footer
            val hasAnyKeys = providerConfigs.values.any { it.apiKeys.isNotEmpty() }
            val configuredCount = providerConfigs.values.count { it.apiKeys.isNotEmpty() }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = if (hasAnyKeys) Icons.Default.Verified else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (hasAnyKeys) stringResource(R.string.providers_configured, configuredCount) else stringResource(R.string.no_api_keys_demo_mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
    apiKeys: List<String>,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit
) {
    // Reuse the generic ProviderSection logic but simplified for Tavily (no models)
    // This ensures UI consistency with other providers
    com.example.smarty.ui.screens.settings.ProviderSection(
        provider = AIProvider.OPENAI, // Dummy provider enum, just for the composable
        providerName = stringResource(R.string.tavily_web_search),
        providerDescription = stringResource(R.string.enable_ai_web_search_capabilities),
        apiKeys = apiKeys,
        isEnabled = true, // Always enabled if keys exist
        selectedModel = "", // No model selection
        availableModels = emptyList(),
        onAddKey = onAddKey,
        onRemoveKey = onRemoveKey,
        onUpdateKey = { _, _ -> }, // Update not supported yet for Tavily in this view
        onToggleEnabled = { }, // Cannot toggle independently of keys
        onSelectModel = { },
        onTestKey = { _, callback -> callback(true) }, // Mock test for now
        keyUsageStats = emptyMap()
    )
}

/**
 * Result of testing connection to local LLM server
 */
private sealed class TestResult {
    data object Success : TestResult()
    data class Failure(val message: String) : TestResult()
}

/**
 * Test connection to local LLM server by pinging the health endpoint
 * Uses OkHttp for reliable SSL handling with self-signed certificates
 */
private suspend fun testLocalServer(ip: String, port: String, useHttps: Boolean): TestResult {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val protocol = if (useHttps) "https" else "http"
            val testUrl = "$protocol://$ip:$port/v1/models"
            
            // Build appropriate OkHttp client
            val client = if (useHttps) {
                // Create trust-all SSL configuration for self-signed certs
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                    object : javax.net.ssl.X509TrustManager {
                        @Throws(java.security.cert.CertificateException::class)
                        override fun checkClientTrusted(
                            chain: Array<java.security.cert.X509Certificate>,
                            authType: String
                        ) {
                            // Trust all client certs
                        }

                        @Throws(java.security.cert.CertificateException::class)
                        override fun checkServerTrusted(
                            chain: Array<java.security.cert.X509Certificate>,
                            authType: String
                        ) {
                            // Trust all server certs - ONLY safe for local LAN!
                        }

                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                )

                // Use TLSv1.2 and TLSv1.3 for maximum compatibility
                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                
                // Create socket factory that enables all TLS versions
                val sslSocketFactory = sslContext.socketFactory

                okhttp3.OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                    .hostnameVerifier { _, _ -> true } // Accept any hostname
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            } else {
                // Plain HTTP - no SSL needed
                okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
            }

            val request = okhttp3.Request.Builder()
                .url(testUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()

            // Any response means server is reachable
            if (code in 200..299 || code in 400..499) {
                TestResult.Success
            } else {
                TestResult.Failure("server_returned:_$code")
            }
        } catch (e: java.net.ConnectException) {
            TestResult.Failure("connection_refused_-_is_server_running?")
        } catch (e: java.net.SocketTimeoutException) {
            TestResult.Failure("timeout_-_check_ip_and_firewall")
        } catch (e: java.net.UnknownHostException) {
            TestResult.Failure("invalid_ip_address")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            // Log the actual error for debugging
            android.util.Log.e("LocalServerTest", "SSL Handshake failed", e)
            TestResult.Failure("ssl_failed_-_ensure_caddy_is_running")
        } catch (e: javax.net.ssl.SSLException) {
            android.util.Log.e("LocalServerTest", "SSL Exception", e)
            TestResult.Failure("ssl_error_-_check_port_(8443_for_https)")
        } catch (e: Exception) {
            android.util.Log.e("LocalServerTest", "connection_error", e)
            val msg = e.message?.lowercase() ?: ""
            when {
                msg.contains("ssl") || msg.contains("tls") -> 
                    TestResult.Failure("ssl/tls_error_-_try_http_mode")
                msg.contains("certificate") -> 
                    TestResult.Failure("cert_error_-_is_caddy_running?")
                msg.contains("reset") || msg.contains("closed") ->
                    TestResult.Failure("connection_reset_-_wrong_port?")
                else -> 
                    TestResult.Failure("error:_${e.message?.take(50) ?: "unknown"}")
            }
        }
    }
}

/**
 * Local LLM Server configuration section.
 * Allows users to connect to local AI server via USB tethering or WiFi.
 * Supports both HTTP (default) and HTTPS (encrypted) connections.
 */
@Composable
private fun LocalServerSection(
    isEnabled: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    serverIP: String,
    serverPort: String,
    useHttps: Boolean,
    onSetServerIP: (String) -> Unit,
    onSetServerPort: (String) -> Unit,
    onSetUseHttps: (Boolean) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf(serverIP) }
    var portInput by remember { mutableStateOf(serverPort.ifBlank { "8000" }) }
    var httpsEnabled by remember { mutableStateOf(useHttps) }
    var isEditing by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    // Detect connection type based on IP range
    val connectionType = when {
        ipInput.startsWith("10.") -> "USB/WiFi"
        ipInput.startsWith("192.168.") -> "WiFi"
        ipInput.startsWith("172.") -> "Ethernet"
        else -> "Network"
    }

    // Sync with external state
    LaunchedEffect(serverIP, serverPort, useHttps) {
        if (!isEditing) {
            ipInput = serverIP
            portInput = serverPort.ifBlank { "8000" }
            httpsEnabled = useHttps
        }
    }

    // Clear test result after 5 seconds
    LaunchedEffect(testResult) {
        if (testResult != null) {
            kotlinx.coroutines.delay(5000)
            testResult = null
        }
    }

    SettingsSection(
        title = stringResource(R.string.local_llm_server),
        icon = Icons.Default.Lan,
        isExpanded = isExpanded,
        onToggle = { isExpanded = !isExpanded }
    ) {
        // Master Toggle
        SettingsToggleRow(
            title = stringResource(R.string.enable_local_connection),
            icon = if (isEnabled) Icons.Default.Sensors else Icons.Default.VisibilityOff,
            isChecked = isEnabled,
            onCheckedChange = onSetEnabled,
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )

        AnimatedVisibility(
            visible = isEnabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column {
                Spacer(modifier = Modifier.height(12.dp))

                // Status Badge if connected/configured
                if (serverIP.isNotBlank() && !isEditing) {
                     Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ConnectionStatusIndicator(
                            status = if (testResult is TestResult.Success) ConnectionStatus.CONNECTED else ConnectionStatus.OFFLINE,
                            showLabel = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = connectionType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Info Text
                Text(
                    text = stringResource(R.string.connect_to_your_local_llm_server_use_usb_ip_for_usb_tethering_or_wifi_ip_for_wireless),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Inputs
                com.example.smarty.ui.components.SettingsInputRow(
                    label = stringResource(R.string.ip_address),
                    value = ipInput,
                    onValueChange = {
                        ipInput = it
                        isEditing = true
                    },
                    placeholder = stringResource(R.string.ip_address_placeholder)
                )

                Spacer(modifier = Modifier.height(12.dp))

                com.example.smarty.ui.components.SettingsInputRow(
                    label = stringResource(R.string.port),
                    value = portInput,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() }) {
                            portInput = newValue
                            isEditing = true
                        }
                    },
                    placeholder = "8000",
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // HTTPS Toggle
                SettingsToggleRow(
                    title = stringResource(R.string.use_https),
                    icon = Icons.Default.Lock,
                    isChecked = httpsEnabled,
                    onCheckedChange = {
                        httpsEnabled = it
                        isEditing = true
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (serverIP.isNotBlank()) {
                        TextButton(
                            onClick = {
                                onSetServerIP("")
                                onSetServerPort("8000")
                                ipInput = ""
                                portInput = "8000"
                                isEditing = false
                                testResult = null
                            },
                            enabled = !isTesting
                        ) {
                            Text(stringResource(R.string.clear), color = MaterialTheme.colorScheme.error)
                        }
                    }

                    Button(
                        onClick = {
                            if (ipInput.isNotBlank()) {
                                scope.launch {
                                    isTesting = true
                                    testResult = null
                                    val port = portInput.ifBlank { "8000" }
                                    val result = testLocalServer(ipInput.trim(), port, httpsEnabled)
                                    isTesting = false
                                    testResult = result

                                    if (result is TestResult.Success) {
                                        onSetServerIP(ipInput.trim())
                                        onSetServerPort(port)
                                        onSetUseHttps(httpsEnabled)
                                        isEditing = false
                                    }
                                }
                            }
                        },
                        enabled = ipInput.isNotBlank() && !isTesting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        if (isTesting) {
                            CalmThinkingDots(
                                color = MaterialTheme.colorScheme.surface,
                                dotSize = 3.dp
                            )
                        } else {
                            Text(stringResource(R.string.test_and_save))
                        }
                    }
                }

                // Test Result Message
                AnimatedVisibility(
                    visible = testResult != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .background(
                                color = if (testResult is TestResult.Success)
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (testResult is TestResult.Success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (testResult is TestResult.Success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (val result = testResult) {
                                    is TestResult.Success -> stringResource(R.string.connection_successful)
                                    is TestResult.Failure -> result.message
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CENTRALIZED SETTINGS COMPONENTS - Minimal, Grouped, Expandable
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Collapsible settings section with header and expandable content.
 * Only shows items when expanded - reduces visual clutter.
 */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val accentColor = LocalAccentColor.current // Use dynamic accent for settings sections
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "rotation"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (isExpanded)
            accentColor.copy(alpha = 0.08f)
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isExpanded) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isExpanded) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Medium
                    ),
                    color = if (isExpanded) accentColor else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "collapse" else "expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer { rotationZ = rotationAngle }
                )
            }

            // Expandable content
            androidx.compose.animation.AnimatedVisibility(
                visible = isExpanded,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Simple settings row inside a section - minimal, tappable.
 */
@Composable
private fun SettingsRow(
    title: String,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false,
    iconColor: Color = LocalAccentColor.current,
    containerColor: Color = Color.Transparent
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Leading Icon with Squircle shape
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                            else if (containerColor != Color.Transparent) containerColor
                            else iconColor.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDestructive) MaterialTheme.colorScheme.error else iconColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = contentColor
                    )
                    subtitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = if (enabled) 0.7f else 0.4f
                            )
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingFlat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Settings row with toggle switch - inline control.
 */
@Composable
private fun SettingsToggleRow(
    title: String,
    icon: ImageVector,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconColor: Color = LocalAccentColor.current,
    containerColor: Color = Color.Transparent
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Leading Icon with Squircle shape
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (containerColor != Color.Transparent) containerColor else iconColor.copy(alpha = 0.1f)),
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
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = iconColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.scale(0.8f)
            )
        }
    }
}

// formatCacheSize is now imported from DataManagementSection

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

