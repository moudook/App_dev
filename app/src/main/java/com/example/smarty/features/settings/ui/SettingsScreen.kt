package com.example.smarty.features.settings.ui

import android.util.Log
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.smarty.R
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager.DeviceCalendar
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.*
import com.example.smarty.ui.theme.*
import androidx.compose.ui.graphics.Brush
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "SettingsScreen"

@OptIn(ExperimentalMaterial3Api::class)
enum class SettingsView {
    Main, Backup, About, ShakeSensitivity, CalendarSelector, ServerConfig, AgentMemory, GuidedBreathing, ToolPermissions
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
    onExportData: () -> Unit = {},
    // Google Calendar Sync
    lastCalendarSyncTime: Long = 0L,
    onCalendarSync: () -> Unit = {},
    // Cloud Sync
    onCloudSync: () -> Unit = {},
    // Shake sensitivity
    shakeSensitivity: Float = 0.5f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    onSignOut: () -> Unit = {},
    onNavigateToTasks: () -> Unit = {},
    onNavigateToTags: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToChatFolders: () -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {}
) {
    val context = LocalContext.current
    val securePrefs = remember { SecurePreferences.getInstance(context) }

    // Navigation State
    var currentView by remember { mutableStateOf(SettingsView.Main) }

    // Grouped section expand states for the Main View
    var expandedSection by remember { mutableStateOf<String?>("ai") }

    val isSystemDark = isDarkTheme

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
                                imageVector = SmartyIcons.Back,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
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
                        if (isLoading) {
                            SettingsMainSkeleton()
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                            Spacer(modifier = Modifier.height(8.dp))

                            // PROFILE HEADER (Mockup matching the screenshot)
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val appName = stringResource(R.string.app_name)
                                val badge = remember(appName) {
                                    appName
                                        .split(" ")
                                        .filter { it.isNotBlank() }
                                        .take(2)
                                        .joinToString("") { it.first().uppercaseChar().toString() }
                                        .ifBlank { "FR" }
                                }
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .background(SemanticColors.warning, CircleShape), // Yellow circle
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = badge,
                                        color = Color.White,
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = appName,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Private workspace on this device",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = { currentView = SettingsView.Backup },
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onBackground,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) // Added subtle background for better contrast
                                    ),
                                    shape = LocalShapes.current.buttonLarge,
                                    modifier = Modifier.height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp)
                                ) {
                                    Text("Open backups", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                }
                            }

                            // SECTION 1: AI & VOICE
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "My Smarty",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                                )
                                SmartySettingsCard {
                                    SmartySettingsRow(
                                        label = "Smarty Server",
                                        icon = SmartyIcons.Cloud,
                                        subtitle = "View connection info",
                                        onClick = { currentView = SettingsView.ServerConfig }
                                    )
                                    SmartySettingsRow(
                                        label = "Agent Memory",
                                        icon = SmartyIcons.Psychology,
                                        subtitle = "View what the agent remembers",
                                        onClick = { currentView = SettingsView.AgentMemory }
                                    )
                                    SmartySettingsRow(
                                        label = "Tool Permissions",
                                        icon = SmartyIcons.Lock,
                                        subtitle = "Override the default tool allow/deny policy",
                                        onClick = { currentView = SettingsView.ToolPermissions }
                                    )
                                }
                            }

                            // SECTION 2: CALENDAR INTEGRATION
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "Calendar Integration",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                                )
                                val handleCalendarSync: (Boolean) -> Unit = { checked ->
                                    onSetCalendarSyncEnabled(checked)
                                    if (checked) onLoadDeviceCalendars()
                                }
                                SmartySettingsCard {
                                    SmartySettingsSwitchRow(
                                        label = stringResource(R.string.sync_to_google_calendar),
                                        icon = SmartyIcons.Refresh,
                                        checked = isCalendarSyncEnabled,
                                        onCheckedChange = handleCalendarSync
                                    )

                                    if (isCalendarSyncEnabled) {
                                        val selectedCalendar = deviceCalendars.find { it.id == targetCalendarId }
                                        SmartySettingsRow(
                                            label = stringResource(R.string.default_calendar),
                                            icon = SmartyIcons.Calendar,
                                            subtitle = selectedCalendar?.displayName?.lowercase() ?: stringResource(R.string.select_calendar),
                                            onClick = { currentView = SettingsView.CalendarSelector }
                                        )
                                    }
                                }
                            }

                            // SECTION 3: DATA
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "Data & Storage",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                                )
                                SmartySettingsCard {
                                    SmartySettingsRow(
                                        label = "Cloud Sync",
                                        icon = SmartyIcons.CloudSync,
                                        subtitle = "Pull down on notes to sync or tap here",
                                        onClick = onCloudSync
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.backup_sync),
                                        icon = SmartyIcons.CloudSync,
                                        subtitle = if (lastBackupTime > 0) {
                                            val sdf = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                                            "${stringResource(R.string.last_)} ${sdf.format(java.util.Date(lastBackupTime))}"
                                        } else stringResource(R.string.not_backed_up),
                                        onClick = { currentView = SettingsView.Backup }
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.google_calendar_sync),
                                        icon = SmartyIcons.Refresh,
                                        subtitle = if (lastCalendarSyncTime > 0) {
                                            val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                                            "${stringResource(R.string.last_sync_)} ${sdf.format(java.util.Date(lastCalendarSyncTime))}"
                                        } else stringResource(R.string.not_synced),
                                        onClick = onCalendarSync
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.export_data),
                                        icon = SmartyIcons.Download,
                                        subtitle = stringResource(R.string.export_all_notes_and_settings),
                                        onClick = {
                                            currentView = SettingsView.Backup
                                            onExportData()
                                        }
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.clear_cache),
                                        icon = SmartyIcons.DeleteOutline,
                                        subtitle = formatCacheSize(cacheSizeBytes),
                                        onClick = onClearCache,
                                        enabled = !isClearingCache && cacheSizeBytes > 0
                                    )
                                }
                            }

                            // SECTION 4: PREFERENCES
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "App Preferences",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                                )
                                SmartySettingsCard {
                                    SmartySettingsSwitchRow(
                                        label = stringResource(R.string.dark_mode),
                                        icon = SmartyIcons.DarkMode,
                                        checked = isDarkTheme,
                                        onCheckedChange = onToggleTheme
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.shake_sensitivity),
                                        icon = SmartyIcons.Vibration,
                                        subtitle = "${(shakeSensitivity * 100).toInt()}% Sensitivity",
                                        onClick = { currentView = SettingsView.ShakeSensitivity }
                                    )
                                    SmartySettingsRow(
                                        label = "Guided Breathing",
                                        icon = SmartyIcons.Games,
                                        subtitle = "5-minute mental break",
                                        onClick = { currentView = SettingsView.GuidedBreathing }
                                    )
                                    SmartySettingsRow(
                                        label = "Tasks",
                                        icon = SmartyIcons.Tasks,
                                        subtitle = "Manage your tasks",
                                        onClick = { onNavigateToTasks() }
                                    )
                                    SmartySettingsRow(
                                        label = "Tags",
                                        icon = SmartyIcons.Tags,
                                        subtitle = "Organize notes with tags",
                                        onClick = { onNavigateToTags() }
                                    )
                                    SmartySettingsRow(
                                        label = "Notifications",
                                        icon = SmartyIcons.Notifications,
                                        subtitle = "View and manage notifications",
                                        onClick = { onNavigateToNotifications() }
                                    )
                                    SmartySettingsRow(
                                        label = "Chat Folders",
                                        icon = SmartyIcons.Folder,
                                        subtitle = "Organize chat sessions into folders",
                                        onClick = { onNavigateToChatFolders() }
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.default_assistant),
                                        icon = SmartyIcons.Assistant,
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
                                                    Log.e(TAG, "Failed to open app settings or fallback settings", e2)
                                                }
                                            }
                                        }
                                    )
                                    SmartySettingsRow(
                                        label = stringResource(R.string.about_smarty),
                                        icon = SmartyIcons.Info,
                                        subtitle = "Version ${stringResource(R.string.smarty_version)}",
                                        onClick = { currentView = SettingsView.About }
                                    )
                                }
                            }

                            // SECTION 5: ACCOUNT
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    text = "Account",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                                )
                                SmartySettingsCard {
                                    SmartySettingsRow(
                                        label = stringResource(R.string.sign_out),
                                        icon = SmartyIcons.Logout,
                                        onClick = onSignOut,
                                        textColor = MaterialTheme.colorScheme.error,
                                        showChevron = false
                                    )
                                }
                            }
                        }
                    }
                }
                // SUB-VIEWS (Replaces Bottom Sheets)
                    SettingsView.ServerConfig -> {
                        ServerConfigView(
                            serverUrl = securePrefs.getServerUrl(),
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
                    SettingsView.CalendarSelector -> {
                         val handleSelect: (Long) -> Unit = { id ->
                             onSetTargetCalendarId(id)
                             currentView = SettingsView.Main 
                         }
                         CalendarSelectorView(
                             calendars = deviceCalendars,
                             selectedId = targetCalendarId,
                             onSelect = handleSelect,
                             onBack = { currentView = SettingsView.Main }
                         )
                    }
                    SettingsView.AgentMemory -> {
                        AgentMemoryView(
                            onBack = { currentView = SettingsView.Main }
                        )
                    }
                    SettingsView.ToolPermissions -> {
                        ToolPermissionsView(
                            onBack = { currentView = SettingsView.Main }
                        )
                    }
                    SettingsView.GuidedBreathing -> {
                        GuidedBreathingView(
                            onBack = { currentView = SettingsView.Main }
                        )
                    }
                }
            }

            val scrimColor by animateColorAsState(
                targetValue = MaterialTheme.colorScheme.background,
                animationSpec = tween(500),
                label = "scrimColor"
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
                    imageVector = SmartyIcons.Back,
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
private fun ServerConfigView(
    serverUrl: String,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsHeader(
            title = "Smarty Server",
            subtitle = "Connection configuration",
            onBack = onBack
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            SmartySettingsCard {
                SmartySettingsRow(
                    label = "Server URL",
                    icon = SmartyIcons.Cloud,
                    subtitle = serverUrl,
                    showChevron = false,
                    enabled = false
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "The server URL is fixed and cannot be changed. This ensures consistent connectivity to the AI service.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Current Server:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                    shape = LocalShapes.current.buttonLarge,
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
                                imageVector = SmartyIcons.Check,
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
            .clip(LocalShapes.current.card)
            .clickable(enabled = enabled, onClick = onClick),
        shape = LocalShapes.current.card,
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
                        contentDescription = null, // Decorative icon - title provides context
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
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = iconColor
                )
            } else if (showArrow) {
                Icon(
                    imageVector = SmartyIcons.ChevronRight,
                    contentDescription = "Navigate to $title settings",
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
            .clip(LocalShapes.current.card)
            .clickable(enabled = enabled) { onCheckedChange(!isChecked) },
        shape = LocalShapes.current.card,
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


// formatCacheSize is now imported from DataManagementSection

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                hide(WindowInsetsCompat.Type.systemBars())
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

@Composable
private fun AgentMemoryView(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<com.example.smarty.data.model.AIMemory>>(emptyList()) }
    var showDeleteAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val dao = com.example.smarty.data.local.SmartyDatabase.getDatabase(context).aiMemoryDao()
            memories = dao.getAllMemories()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onBack() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Agent Memory",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            if (memories.isNotEmpty()) {
                TextButton(onClick = { showDeleteAll = true }) {
                    Text(
                        text = "Clear All",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        if (memories.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No memories yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "I\'ll remember things as we chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            val grouped = memories.groupBy { it.type }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                grouped.forEach { (type, memoryList) ->
                    item {
                        Text(
                            text = type.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    items(memoryList, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onDelete = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.example.smarty.data.local.SmartyDatabase.getDatabase(context)
                                            .aiMemoryDao().deleteMemoryById(memory.id)
                                        memories = com.example.smarty.data.local.SmartyDatabase.getDatabase(context)
                                            .aiMemoryDao().getAllMemories()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDeleteAll = false },
            title = { Text("Forget everything?") },
            text = { Text("I\'ll lose everything I\'ve learned about you.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAll = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            com.example.smarty.data.local.SmartyDatabase.getDatabase(context)
                                .aiMemoryDao().clearAllMemories()
                            memories = emptyList()
                        }
                    }
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAll = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MemoryCard(
    memory: com.example.smarty.data.model.AIMemory,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = memory.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Used ${memory.usageCount}x",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    val confidencePercent = (memory.confidence * 100).toInt()
                    Text(
                        text = "$confidencePercent% confident",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Settings → Tool Permissions
 *
 * Lets the user override the static SMARTY_DEFAULT policy per tool.
 * Calls:
 *   GET  /api/v1/permissions/tools
 *   PUT  /api/v1/permissions/tools/{toolName}  (decision ∈ ALLOW|DENY|INHERIT)
 *
 * 3-way segmented selector per row:
 *   • Default — fall back to SMARTY_DEFAULT
 *   • Allow   — always auto-approve this tool
 *   • Deny    — always block this tool
 */
@Composable
private fun ToolPermissionsView(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as? android.app.Application
    val remoteAgentService = remember(app) {
        app?.let { com.example.smarty.di.ServiceLocator.provideRemoteAgentService(it) }
    }

    var tools by remember { mutableStateOf<List<com.example.smarty.data.remote.ToolPermissionDto>>(emptyList()) }
    var defaultPolicy by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pendingTool by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        isLoading = true
        errorMessage = null
        val svc = remoteAgentService
        if (svc == null) {
            errorMessage = "Remote service unavailable."
            isLoading = false
            return
        }
        val resp = svc.getToolPermissions()
        if (resp == null) {
            errorMessage = "Could not load permissions. Check your connection."
        } else {
            tools = resp.tools
            defaultPolicy = resp.defaultPolicy
        }
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onBack() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Tool Permissions",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { scope.launch { reload() } }) {
                        Text("Retry")
                    }
                }
            }
        } else {
            // Explanatory card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Override the agent's default tool policy.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Allow auto-approves the tool. Deny blocks it. Default falls back to Smarty's built-in policy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tools, key = { it.toolName }) { tool ->
                    val isPending = pendingTool == tool.toolName
                    ToolPermissionRow(
                        tool = tool,
                        defaultDecision = defaultPolicy[tool.toolName] ?: "DEFAULT",
                        isPending = isPending,
                        onDecisionChange = { newDecision ->
                            pendingTool = tool.toolName
                            scope.launch {
                                val svc = remoteAgentService
                                if (svc == null) {
                                    errorMessage = "Remote service unavailable."
                                    pendingTool = null
                                    return@launch
                                }
                                val resp = svc.setToolPermission(
                                    toolName = tool.toolName,
                                    decision = newDecision,
                                )
                                if (resp == null) {
                                    errorMessage = "Failed to save override for ${tool.toolName}"
                                } else {
                                    // Refresh from server to get the canonical state.
                                    reload()
                                }
                                pendingTool = null
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolPermissionRow(
    tool: com.example.smarty.data.remote.ToolPermissionDto,
    defaultDecision: String,
    isPending: Boolean,
    onDecisionChange: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tool.toolName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (isPending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (tool.isOverridden) {
                    Spacer(modifier = Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("override", fontSize = 10.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            labelColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ),
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 3-way segmented selector
            val effectiveForUi = if (tool.isOverridden) tool.decision else "DEFAULT"
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = effectiveForUi == "DEFAULT",
                    onClick = { onDecisionChange("INHERIT") },
                    shape = SegmentedButtonDefaults.itemShape(0, 3),
                ) {
                    Text("Default", fontSize = 11.sp)
                }
                SegmentedButton(
                    selected = effectiveForUi == "ALLOW",
                    onClick = { onDecisionChange("ALLOW") },
                    shape = SegmentedButtonDefaults.itemShape(1, 3),
                ) {
                    Text("Allow", fontSize = 11.sp)
                }
                SegmentedButton(
                    selected = effectiveForUi == "DENY",
                    onClick = { onDecisionChange("DENY") },
                    shape = SegmentedButtonDefaults.itemShape(2, 3),
                ) {
                    Text("Deny", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Default policy: $defaultDecision",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GuidedBreathingView(
    onBack: () -> Unit
) {
    var isRunning by remember { mutableStateOf(true) }
    var cycleCount by remember { mutableIntStateOf(0) }
    var phase by remember { mutableIntStateOf(0) } // 0=inhale, 1=hold, 2=exhale, 3=hold
    var elapsedTime by remember { mutableLongStateOf(0L) }
    
    val phaseText = when (phase) {
        0 -> "Breathe In"
        1 -> "Hold"
        2 -> "Breathe Out"
        else -> "Rest"
    }
    
    val totalCycleTime = 16000L // 4 phases * 4 seconds
    
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isRunning) {
                delay(100)
                elapsedTime += 100
                val newPhase = ((elapsedTime / 4000) % 4).toInt()
                if (newPhase != phase) {
                    phase = newPhase
                    if (phase == 0 && elapsedTime > 1000) cycleCount++
                }
                if (cycleCount >= 5) {
                    isRunning = false
                }
            }
        }
    }
    
    // Calculate scale based on elapsed time in current cycle
    val timeInPhase = (elapsedTime % 4000).toFloat() / 4000f
    val targetScale = when (phase) {
        0 -> 0.6f + (0.4f * timeInPhase) // Grow from 0.6 to 1.0
        1 -> 1f // Hold at max
        2 -> 1f - (0.4f * timeInPhase) // Shrink from 1.0 to 0.6
        else -> 0.6f // Hold at min
    }
    
    val animatedScale by animateFloatAsState(
        targetValue = if (isRunning) targetScale else 0.8f,
        animationSpec = tween(100),
        label = "breathing"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SettingsHeader(
            title = "Guided Breathing",
            subtitle = "Take a mental break",
            onBack = onBack
        )
        
        Spacer(modifier = Modifier.height(48.dp))
        
        // Breathing circle
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(animatedScale),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.size(200.dp),
                shape = CircleShape,
                color = LocalAccentColor.current.copy(alpha = 0.3f)
            ) {}
            Surface(
                modifier = Modifier
                    .size(160.dp)
                    .scale(animatedScale * 0.8f),
                shape = CircleShape,
                color = LocalAccentColor.current.copy(alpha = 0.5f)
            ) {}
            Surface(
                modifier = Modifier
                    .size(120.dp)
                    .scale(animatedScale * 0.6f),
                shape = CircleShape,
                color = LocalAccentColor.current
            ) {}
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Text(
            text = phaseText,
            style = MaterialTheme.typography.headlineMedium,
            color = LocalAccentColor.current
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Cycle ${cycleCount + 1} of 5",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = { 
                    isRunning = !isRunning 
                }
            ) {
                Text(if (isRunning) "Pause" else "Resume")
            }
            Button(
                onClick = onBack
            ) {
                Text("Done")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Each cycle: 4s inhale, 4s hold, 4s exhale, 4s rest",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
