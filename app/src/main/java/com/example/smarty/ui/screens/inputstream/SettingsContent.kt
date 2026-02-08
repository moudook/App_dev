package com.example.smarty.ui.screens.inputstream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.data.model.AIMemory
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.ShakeSensitivityControl
import com.example.smarty.ui.screens.settings.AIMemorySettingsContent
import com.example.smarty.ui.screens.settings.formatCacheSize
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.calendar.GoogleCalendarSyncManager.DeviceCalendar

/**
 * Inline settings content with "Chroma Studio" aesthetic.
 * Now features an inline, minimalistic AI Model configuration section.
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
    aiConfigContent: @Composable (() -> Unit) -> Unit, // Unused now, but kept for signature compatibility
    backupContent: @Composable (() -> Unit) -> Unit,
    // Content padding
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    // AI Memory
    aiMemories: List<AIMemory> = emptyList(),
    onDeleteAIMemory: (AIMemory) -> Unit = {},
    onClearAllAIMemories: () -> Unit = {},
    // Navigation for Activities
    onNavigateToTicTacToe: () -> Unit = {},
    onNavigateToCoinToss: () -> Unit = {},
    // Memory sync
    onSyncAIMemories: () -> Unit = {},
    isMemorySyncInProgress: Boolean = false,
    memorySyncResult: String? = null,
    unreadForMemoryCount: Int = 0,
    onClearMemorySyncResult: () -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {},
    // Local PC
    isLocalPCEnabled: Boolean = false,
    onSetLocalPCEnabled: (Boolean) -> Unit = {},
    localServerIP: String = "",
    localServerPort: String = "",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    onTestLocalServer: (String, String, Boolean, (com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit = { _, _, _, _ -> }
) {
    val accentColor = LocalAccentColor.current
    val context = LocalContext.current
    val shapes = LocalShapes.current

    // Sub-sheet states
    var showBackupSheet by remember { mutableStateOf(false) }
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showAIMemorySheet by remember { mutableStateOf(false) }
    var showCalendarSelectorSheet by remember { mutableStateOf(false) }

    // Inline Expansion States for AI Providers
    val expandedProviders = remember { mutableStateMapOf<String, Boolean>() }

    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // --- AI MODELS SECTION (Replaces Pro Banner) ---
        item {
            Column {
                Text(
                    text = stringResource(R.string.ai_models),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )

                SettingsCard {
                    // 1. Web Search (Tavily)
                    InlineTavilyConfig(
                        isExpanded = expandedProviders["tavily"] == true,
                        onExpandChange = { expandedProviders["tavily"] = it },
                        isEnabled = tavilyApiKeys.isNotEmpty(), // Simplified enable check based on key presence
                        onEnabledChange = { /* No-op, driven by key presence */ },
                        apiKeys = tavilyApiKeys,
                        onAddKey = onAddTavilyApiKey,
                        onRemoveKey = onRemoveTavilyApiKey
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )

                    // 2. Local LLM
                    InlineLocalPCConfig(
                        isExpanded = expandedProviders["local"] == true,
                        onExpandChange = { expandedProviders["local"] = it },
                        isEnabled = isLocalPCEnabled,
                        onEnabledChange = onSetLocalPCEnabled,
                        ip = localServerIP,
                        port = localServerPort,
                        useHttps = localServerUseHttps,
                        onIpChange = onSetLocalServerIP,
                        onPortChange = onSetLocalServerPort,
                        onHttpsChange = onSetLocalServerUseHttps,
                        onTestConnection = onTestLocalServer
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(start = 56.dp, end = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )

                    // 3. Cloud Providers
                    val providers = (providerPriorityOrder.filter { it != AIProvider.LOCAL_PC } +
                                   (AIProvider.entries - providerPriorityOrder - AIProvider.LOCAL_PC)).distinct()

                    providers.forEachIndexed { index, provider ->
                        val config = providerConfigs[provider]
                        if (config != null) {
                            InlineProviderConfig(
                                provider = provider,
                                config = config,
                                isExpanded = expandedProviders[provider.name] == true,
                                onExpandChange = { expandedProviders[provider.name] = it },
                                onEnabledChange = { onSetProviderEnabled(provider, it) },
                                onAddKey = { onAddApiKey(provider, it) },
                                onRemoveKey = { onRemoveApiKey(provider, it) },
                                onUpdateKey = { old, new -> onUpdateApiKey(provider, old, new) },
                                onSelectModel = { onSetSelectedModel(provider, it) },
                                availableModels = getAvailableModels(provider),
                                onRefreshModels = { onRefreshModels(provider) }
                            )

                            if (index < providers.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 56.dp, end = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                                )
                            }
                        }
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
                SettingsItem(
                    icon = Icons.Default.Games,
                    label = "Mental Break",
                    onClick = onNavigateToTicTacToe,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Default.Casino,
                    label = "Coin Toss",
                    onClick = onNavigateToCoinToss,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsSwitch(
                    icon = Icons.Default.Brightness4,
                    label = stringResource(R.string.dark_mode),
                    checked = isDarkTheme,
                    onCheckedChange = onToggleTheme,
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
                    textColor = Color(0xFFFF3B30),
                    iconColor = Color(0xFFFF3B30),
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

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // -- Sheets --
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
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        ShakeSensitivityControl(sensitivity = shakeSensitivity, onSensitivityChange = onShakeSensitivityChange)
                    }
                }
            }
        }
    }

    if (showCalendarSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCalendarSelectorSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
                Text(
                    text = stringResource(R.string.select_default_calendar),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(deviceCalendars.size) { index ->
                        val calendar = deviceCalendars[index]
                        val isSelected = calendar.id == targetCalendarId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onSetTargetCalendarId(calendar.id); showCalendarSelectorSheet = false },
                            shape = RoundedCornerShape(26.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, accentColor) else null
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(calendar.color?.let { Color(it) } ?: Color.Gray))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(calendar.displayName, style = MaterialTheme.typography.titleMedium, color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface)
                                    Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isSelected) Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Inline AI Config Components ---

@Composable
fun InlineTavilyConfig(
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    apiKeys: List<String>,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit
) {
    Column {
        InlineHeader(
            icon = Icons.Default.Public,
            title = "Web Search (Tavily)",
            isExpanded = isExpanded,
            onExpandChange = onExpandChange,
            isEnabled = isEnabled,
            onEnabledChange = onEnabledChange,
            hasSwitch = false // Always on if key present
        )

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                Text("API Key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                if (apiKeys.isEmpty()) {
                    ApiKeyInput(
                        value = "",
                        onValueChange = { if(it.isNotEmpty()) onAddKey(it) },
                        placeholder = "tvly-..."
                    )
                } else {
                    apiKeys.forEach { key ->
                        ApiKeyRow(key = key, onRemove = { onRemoveKey(key) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    var newKey by remember { mutableStateOf("") }
                    if (newKey.isNotEmpty() || apiKeys.size < 5) {
                        ApiKeyInput(
                            value = newKey,
                            onValueChange = { newKey = it },
                            placeholder = "Add another key",
                            onDone = {
                                if (newKey.isNotBlank()) {
                                    onAddKey(newKey)
                                    newKey = ""
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InlineLocalPCConfig(
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    ip: String,
    port: String,
    useHttps: Boolean,
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onHttpsChange: (Boolean) -> Unit,
    onTestConnection: (String, String, Boolean, (com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit
) {
    // Local state to prevent input lag/resetting from async updates
    var localIp by remember(ip) { mutableStateOf(ip) }
    var localPort by remember(port) { mutableStateOf(port) }
    var isEditing by remember { mutableStateOf(false) }

    // Test state
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult?>(null) }
    
    // Clear test result after 5 seconds
    LaunchedEffect(testResult) {
        if (testResult != null) {
            kotlinx.coroutines.delay(5000)
            testResult = null
        }
    }

    // Sync from props only if not editing (or if expanded changed, implying fresh look)
    LaunchedEffect(ip, port, isExpanded) {
        if (!isEditing) {
            localIp = ip
            localPort = port
        }
    }

    Column {
        InlineHeader(
            icon = Icons.Default.Computer,
            title = "Local LLM Server",
            isExpanded = isExpanded,
            onExpandChange = onExpandChange,
            isEnabled = isEnabled,
            onEnabledChange = onEnabledChange
        )

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(2f)) {
                        Text("IP Address", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = localIp,
                            onValueChange = {
                                localIp = it
                                isEditing = true
                                onIpChange(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                cursorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Port", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = localPort,
                            onValueChange = {
                                localPort = it
                                isEditing = true
                                onPortChange(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                cursorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useHttps,
                        onCheckedChange = onHttpsChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        text = "Use HTTPS (Secure)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    
                    // Test Button
                    Button(
                        onClick = {
                            if (localIp.isNotBlank()) {
                                isTesting = true
                                testResult = null
                                onTestConnection(localIp.trim(), localPort.ifBlank { "8000" }, useHttps) { result ->
                                    isTesting = false
                                    testResult = result
                                    // If success, save implicitly (already saved by onIpChange/etc, but good to confirm)
                                }
                            }
                        },
                        enabled = localIp.isNotBlank() && !isTesting,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                         if (isTesting) {
                             CircularProgressIndicator(
                                 modifier = Modifier.size(16.dp),
                                 color = MaterialTheme.colorScheme.onPrimary,
                                 strokeWidth = 2.dp
                             )
                         } else {
                             Text("Test Connection")
                         }
                    }
                }
                
                // Test Result Message
                AnimatedVisibility(visible = testResult != null) {
                    val result = testResult
                    if (result != null) {
                        val isSuccess = result is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success
                        val message = if (isSuccess) "Connected successfully!" else (result as com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Failure).message
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InlineProviderConfig(
    provider: AIProvider,
    config: AIProviderConfig,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onUpdateKey: (String, String) -> Unit,
    onSelectModel: (String) -> Unit,
    availableModels: List<Pair<String, String>>,
    onRefreshModels: () -> Unit
) {
    Column {
        InlineHeader(
            icon = when(provider) {
                AIProvider.OPENAI -> Icons.Default.SmartToy
                AIProvider.ANTHROPIC -> Icons.Default.Psychology
                AIProvider.GEMINI -> Icons.Default.AutoAwesome
                else -> Icons.Default.SmartToy
            },
            title = provider.displayName,
            isExpanded = isExpanded,
            onExpandChange = onExpandChange,
            isEnabled = config.isEnabled,
            onEnabledChange = onEnabledChange
        )

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                // API Keys
                Text("API Key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                if (config.apiKeys.isEmpty()) {
                     ApiKeyInput(
                        value = "",
                        onValueChange = { if(it.isNotBlank()) onAddKey(it) },
                        placeholder = "sk-..."
                    )
                } else {
                    config.apiKeys.forEach { key ->
                         ApiKeyRow(key = key, onRemove = { onRemoveKey(key) })
                         Spacer(modifier = Modifier.height(8.dp))
                    }
                    var newKey by remember { mutableStateOf("") }
                    // Simple logic: show input if < 1 key or if typing new one
                    if (config.apiKeys.isEmpty() || newKey.isNotEmpty()) {
                         ApiKeyInput(
                            value = newKey,
                            onValueChange = { newKey = it },
                            onDone = {
                                if(newKey.isNotBlank()) { onAddKey(newKey); newKey = "" }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Model Selector
                Text("Model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = availableModels.find { it.first == config.selectedModel }?.second ?: config.selectedModel,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        availableModels.forEach { (id, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onSelectModel(id)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InlineHeader(
    icon: ImageVector,
    title: String,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    hasSwitch: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandChange(!isExpanded) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        if (hasSwitch) {
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.scale(0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = if(isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ApiKeyInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Enter API Key",
    onDone: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
fun ApiKeyRow(key: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "••••••••${key.takeLast(4)}",
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
        }
    }
}

// Components from previous file needed for layout
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
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
    containerColor: Color = Color.Transparent,
    showChevron: Boolean = true,
    showDivider: Boolean = false,
    enabled: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (enabled) iconColor else iconColor.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = if (enabled) textColor else textColor.copy(alpha = 0.4f), modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            if (showChevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 78.dp, end = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
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
    showDivider: Boolean = false,
    accentColor: Color = LocalAccentColor.current
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.scale(0.8f)
            )
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 78.dp, end = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
    }
}
