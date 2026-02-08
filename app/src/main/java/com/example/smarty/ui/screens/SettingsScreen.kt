package com.example.smarty.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import com.example.smarty.ui.components.ConnectionStatusIndicator
import com.example.smarty.ui.components.ConnectionStatus
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import com.example.smarty.R
import com.example.smarty.data.model.AIMemory
import com.example.smarty.calendar.GoogleCalendarSyncManager.DeviceCalendar
import com.example.smarty.ui.components.CompactEmptyState
import com.example.smarty.ui.components.SettingsInputRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.example.smarty.ui.LocalAccentColor
import androidx.compose.ui.graphics.Brush
import com.example.smarty.ui.screens.settings.AIMemorySettingsContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.smarty.ui.components.OrganicThinkingIndicator
import com.example.smarty.ui.components.ShakeSensitivityControl
import com.example.smarty.ui.components.UnifiedBottomSheet
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SystemGreen
import com.example.smarty.ui.theme.softCardShadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@OptIn(ExperimentalMaterial3Api::class)
enum class SettingsView {
    Main, Backup, About, ShakeSensitivity, AIMemory, CalendarSelector, ServerConfig
}

/**
 * Duolingo-style Settings Screen
 * Clean, simple, minimal scrolling required
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onBackClick: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    // Embedded Content Slots
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
    // Local LLM Server (USB/WiFi)
    isLocalPCEnabled: Boolean = false,
    onSetLocalPCEnabled: (Boolean) -> Unit = {},
    localServerIP: String = "",
    localServerPort: String = "1234",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    onTestLocalServer: (String, String, Boolean, (com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier,
    onSignOut: () -> Unit = {},
    onNavigateToCoinToss: () -> Unit = {},
    onNavigateToTicTacToe: () -> Unit = {},
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
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {}
) {
    // Navigation State
    var currentView by remember { mutableStateOf(SettingsView.Main) }

    // Grouped section expand states for the Main View
    var expandedSection by remember { mutableStateOf<String?>("ai") }

    // Local LLM connection state
    var localPCTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingLocalPC by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val isSystemDark = isSystemInDarkTheme()
    val context = LocalContext.current

    // Intercept system back button
    androidx.activity.compose.BackHandler(enabled = currentView != SettingsView.Main) {
        currentView = SettingsView.Main
    }

    Scaffold(
        topBar = {
            // Only show main TopAppBar on Main view
            if (currentView == SettingsView.Main) {
                CenterAlignedTopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "My",
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.Light,
                                    fontSize = 32.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.settings_),
                                style = MaterialTheme.typography.headlineLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 32.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = currentView,
                transitionSpec = {
                    if (targetState != SettingsView.Main) {
                        (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> -width } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                            slideOutHorizontally { width -> width } + fadeOut())
                    }
                },
                label = "SettingsNavigation"
            ) { view ->
                when (view) {
                    SettingsView.Main -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))

                            // SECTION 1: AI & VOICE
                            SettingsSection(
                                title = stringResource(R.string.ai_voice),
                                icon = Icons.Default.Assistant,
                                isExpanded = expandedSection == "ai",
                                onToggle = { expandedSection = if (expandedSection == "ai") null else "ai" }
                            ) {
                                SettingsRow(
                                    title = "Smarty Server",
                                    icon = Icons.Default.Cloud,
                                    subtitle = "Configure remote connection",
                                    onClick = { currentView = SettingsView.ServerConfig },
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                                SettingsRow(
                                    title = stringResource(R.string.ai_memory),
                                    icon = Icons.Default.Assistant,
                                    subtitle = if (aiMemories.isEmpty()) stringResource(R.string.no_memories) else "${aiMemories.size} ${stringResource(R.string.memories)}",
                                    onClick = { currentView = SettingsView.AIMemory },
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                            }

                            // SECTION 2: CALENDAR INTEGRATION
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
                                        onClick = { currentView = SettingsView.CalendarSelector },
                                        iconColor = LocalAccentColor.current,
                                        containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                    )
                                }
                            }

                            // SECTION 3: DATA
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
                                    onClick = { currentView = SettingsView.Backup },
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
                                    title = stringResource(R.string.clear_cache),
                                    icon = Icons.Filled.DeleteOutline,
                                    subtitle = formatCacheSize(cacheSizeBytes),
                                    onClick = onClearCache,
                                    enabled = !isClearingCache && cacheSizeBytes > 0,
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                            }

                            // SECTION 4: PREFERENCES
                            SettingsSection(
                                title = stringResource(R.string.preferences),
                                icon = Icons.Default.Settings,
                                isExpanded = expandedSection == "prefs",
                                onToggle = { expandedSection = if (expandedSection == "prefs") null else "prefs" }
                            ) {
                                SettingsRow(
                                    title = stringResource(R.string.shake_sensitivity),
                                    icon = Icons.Filled.Waves,
                                    subtitle = "${(shakeSensitivity * 100).toInt()}% Sensitivity",
                                    onClick = { currentView = SettingsView.ShakeSensitivity },
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                                SettingsRow(
                                    title = "Coin Toss",
                                    icon = Icons.Default.MonetizationOn,
                                    subtitle = "Flip a coin to decide",
                                    onClick = {
                                        android.widget.Toast.makeText(context, "Opening Coin Toss...", android.widget.Toast.LENGTH_SHORT).show()
                                        onNavigateToCoinToss()
                                    },
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                                // Assistant Settings (Intent Launchers don't need a sub-view)
                                SettingsRow(
                                    title = stringResource(R.string.default_assistant),
                                    icon = Icons.Default.Build,
                                    subtitle = stringResource(R.string.set_as_device_assistant),
                                    onClick = {
                                        try {
                                            val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            try {
                                                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                                context.startActivity(intent)
                                            } catch (e2: Exception) {
                                                // Log error
                                            }
                                        }
                                    },
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                                SettingsRow(
                                    title = stringResource(R.string.about_smarty),
                                    icon = Icons.Default.Info,
                                    subtitle = "Version ${stringResource(R.string.smarty_version)}",
                                    onClick = { currentView = SettingsView.About },
                                    iconColor = LocalAccentColor.current,
                                    containerColor = LocalAccentColor.current.copy(alpha = 0.1f)
                                )
                            }

                            // SECTION 5: ACCOUNT
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
                                shape = RoundedCornerShape(26.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(0.5.dp, Color(0xFFFF3B30).copy(alpha = 0.2f)),
                                onClick = onSignOut
                            ) {
                                Row(
                                    modifier = Modifier.padding(20.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Logout,
                                        contentDescription = null,
                                        tint = Color(0xFFFF3B30),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(
                                        text = stringResource(R.string.sign_out),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color(0xFFFF3B30)
                                    )
                                }
                            }

                            // Padding for scrolling
                            Spacer(modifier = Modifier.height(180.dp))
                        }
                    }

                    // SUB-VIEWS (Replaces Bottom Sheets)
                    SettingsView.ServerConfig -> {
                        ServerConfigView(
                            isLocalPCEnabled = isLocalPCEnabled,
                            onSetLocalPCEnabled = onSetLocalPCEnabled,
                            localServerIP = localServerIP,
                            localServerPort = localServerPort,
                            localServerUseHttps = localServerUseHttps,
                            onSetLocalServerIP = onSetLocalServerIP,
                            onSetLocalServerPort = onSetLocalServerPort,
                            onSetLocalServerUseHttps = onSetLocalServerUseHttps,
                            onTestLocalServer = onTestLocalServer,
                            onBack = { currentView = SettingsView.Main }
                        )
                    }
                    SettingsView.ShakeSensitivity -> {
                        ShakeSensitivityView(
                           sensitivity = shakeSensitivity,
                           onSensitivityChange = onShakeSensitivityChange,
                           onBack = { currentView = SettingsView.Main }
                        )
                    }
                    SettingsView.Backup -> {
                        BackupView(
                            content = backupContent,
                            onBack = { currentView = SettingsView.Main }
                        )
                    }
                    SettingsView.About -> {
                        AboutView(onBack = { currentView = SettingsView.Main })
                    }
                    SettingsView.AIMemory -> {
                        AIMemoryView(
                            memories = aiMemories,
                            onDeleteMemory = onDeleteAIMemory,
                            onClearAllMemories = onClearAllAIMemories,
                            onDismiss = { currentView = SettingsView.Main },
                            onSyncMemories = onSyncAIMemories,
                            isSyncing = isMemorySyncInProgress,
                            syncResult = memorySyncResult,
                            unreadNotesCount = unreadForMemoryCount,
                            onClearSyncResult = onClearMemorySyncResult
                        )
                    }
                    SettingsView.CalendarSelector -> {
                         CalendarSelectorView(
                             calendars = deviceCalendars,
                             selectedId = targetCalendarId,
                             onSelect = { 
                                 onSetTargetCalendarId(it)
                                 currentView = SettingsView.Main 
                             },
                             onBack = { currentView = SettingsView.Main }
                         )
                    }
                }
            }

            // Bottom Gradient Scrim (Applied Globally as requested)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                MaterialTheme.colorScheme.background.copy(alpha = 0.8f),
                                MaterialTheme.colorScheme.background
                            )
                        )
                    )
            )
        }
    }
}

// Sub-screen definitions moved to separate composables
@Composable
private fun SettingsHeader(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 16.dp, bottom = 8.dp)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterStart
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            Column(
                 modifier = Modifier.align(Alignment.Center),
                 horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
        )
    }
}

@Composable
private fun AboutView(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.about_smarty),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
             Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(180.dp))
        }
    }
}

@Composable
private fun ShakeSensitivityView(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.shake_gesture),
            subtitle = stringResource(R.string.configure_chat_mode_activation),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            ShakeSensitivityControl(
                sensitivity = sensitivity,
                onSensitivityChange = onSensitivityChange
            )
            Spacer(modifier = Modifier.height(180.dp))
        }
    }
}

@Composable
private fun AIMemoryView(
    memories: List<AIMemory>,
    onDeleteMemory: (AIMemory) -> Unit,
    onClearAllMemories: () -> Unit,
    onDismiss: () -> Unit,
    onSyncMemories: () -> Unit,
    isSyncing: Boolean,
    syncResult: String?,
    unreadNotesCount: Int,
    onClearSyncResult: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.ai_memory),
            subtitle = stringResource(R.string.context_records),
            onBack = onDismiss
        )
        Box(modifier = Modifier.weight(1f)) {
            AIMemorySettingsContent(
                memories = memories,
                onDeleteMemory = onDeleteMemory,
                onClearAllMemories = onClearAllMemories,
                onDismiss = onDismiss,
                onSyncMemories = onSyncMemories,
                isSyncing = isSyncing,
                syncResult = syncResult,
                unreadNotesCount = unreadNotesCount,
                onClearSyncResult = onClearSyncResult
            )
        }
    }
}


@Composable
private fun CalendarSelectorView(
    calendars: List<DeviceCalendar>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.select_default_calendar),
            onBack = onBack
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 180.dp) // Scrim padding
        ) {
            items(calendars.size) { index ->
                val calendar = calendars[index]
                val isSelected = calendar.id == selectedId

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(calendar.id) },
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

            if (calendars.isEmpty()) {
                item {
                    CompactEmptyState(
                        title = stringResource(R.string.calendars),
                        subtitle = stringResource(R.string.no_calendars_found_or_permission_denied),
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupView(
    content: @Composable (() -> Unit) -> Unit,
    onBack: () -> Unit
) {
     Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.backup_sync),
            subtitle = stringResource(R.string.cloud_and_local_archives),
            onBack = onBack
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 32.dp)
        ) {
            content(onBack)
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
            // Icon with directional glow
            Box(modifier = Modifier.size(40.dp)) {
                Box(
                     modifier = Modifier
                         .fillMaxSize()
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
                // Directional inner glow from bottom-right
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                ) {
                    val radius = size.minDimension / 2
                    val centerX = size.width * 0.7f
                    val centerY = size.height * 0.7f
                    
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                            radius = radius * 0.8f
                        )
                    )
                }
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
                OrganicThinkingIndicator(
                    size = 20.dp
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
            // Icon with directional glow
            Box(modifier = Modifier.size(40.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                // Directional inner glow from bottom-right
                androidx.compose.foundation.Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                ) {
                    val radius = size.minDimension / 2
                    val centerX = size.width * 0.7f
                    val centerY = size.height * 0.7f
                    
                    drawCircle(
                        brush = androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.25f),
                                Color.Transparent
                            ),
                            center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                            radius = radius * 0.8f
                        )
                    )
                }
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
    onSetUseHttps: (Boolean) -> Unit,
    onTestConnection: (String, String, Boolean, (com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    var ipInput by remember { mutableStateOf(serverIP) }
    var portInput by remember { mutableStateOf(serverPort.ifBlank { "8000" }) }
    var httpsEnabled by remember { mutableStateOf(useHttps) }
    var isEditing by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult?>(null) }
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
                            status = if (testResult is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success) ConnectionStatus.CONNECTED else ConnectionStatus.OFFLINE,
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
                SettingsInputRow(
                    label = stringResource(R.string.ip_address),
                    value = ipInput,
                    onValueChange = {
                        ipInput = it
                        isEditing = true
                    },
                    placeholder = stringResource(R.string.ip_address_placeholder)
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsInputRow(
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
                                isTesting = true
                                testResult = null
                                val port = portInput.ifBlank { "8000" }
                                
                                onTestConnection(ipInput.trim(), port, httpsEnabled) { result ->
                                    isTesting = false
                                    testResult = result

                                    if (result is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success) {
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
                            OrganicThinkingIndicator(
                                size = 20.dp,
                                baseColor = MaterialTheme.colorScheme.surface
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
                                color = if (testResult is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success)
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                                else
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (testResult is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (testResult is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (val result = testResult) {
                                    is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Success -> stringResource(R.string.connection_successful)
                                    is com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult.Failure -> result.message
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .softCardShadow(elevation = if (isExpanded) 6.dp else 2.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
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
                    tint = if (isExpanded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (isExpanded) androidx.compose.ui.text.font.FontWeight.SemiBold
                        else androidx.compose.ui.text.font.FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = containerColor,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(26.dp),
        border = if (enabled) BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 12.dp),
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
                            if (isDestructive) Color(0xFFFF3B30).copy(alpha = 0.1f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isDestructive) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurface,
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
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = MonoFont
                            ),
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(26.dp)),
        color = containerColor,
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
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
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
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
                    checkedTrackColor = LocalAccentColor.current,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                ),
                modifier = Modifier.scale(0.8f)
            )
        }
    }
}

// formatCacheSize is now imported from DataManagementSection

@Composable
private fun ServerConfigView(
    isLocalPCEnabled: Boolean,
    onSetLocalPCEnabled: (Boolean) -> Unit,
    localServerIP: String,
    localServerPort: String,
    localServerUseHttps: Boolean,
    onSetLocalServerIP: (String) -> Unit,
    onSetLocalServerPort: (String) -> Unit,
    onSetLocalServerUseHttps: (Boolean) -> Unit,
    onTestLocalServer: (String, String, Boolean, (com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = stringResource(R.string.local_llm_server),
            subtitle = stringResource(R.string.configure_local_pc_connection),
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            LocalServerSection(
                isEnabled = isLocalPCEnabled,
                onSetEnabled = onSetLocalPCEnabled,
                serverIP = localServerIP,
                serverPort = localServerPort,
                useHttps = localServerUseHttps,
                onSetServerIP = onSetLocalServerIP,
                onSetServerPort = onSetLocalServerPort,
                onSetUseHttps = onSetLocalServerUseHttps,
                onTestConnection = onTestLocalServer
            )
            Spacer(modifier = Modifier.height(180.dp))
        }
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

/**
 * Format cache size for display.
 */
@Composable
fun formatCacheSize(bytes: Long): String {
    return when {
        bytes <= 0 -> stringResource(R.string.no_cache)
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

