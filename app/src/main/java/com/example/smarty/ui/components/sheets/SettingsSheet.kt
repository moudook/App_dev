package com.example.smarty.ui.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.ui.screens.SettingsScreen
import com.example.smarty.util.api.KeyUsageStats

/**
 * Settings as a full-page overlay.
 * Completely covers the main screen for focused settings access.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    sheetState: SheetState, // Keep for API compatibility, but not used
    onDismiss: () -> Unit,
    // All settings props
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    providerPriorityOrder: List<AIProvider>,
    isDarkTheme: Boolean,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    tavilyApiKeys: List<String>,
    onAddTavilyApiKey: (String) -> Unit,
    onRemoveTavilyApiKey: (String) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean,
    shakeSensitivity: Float,
    onShakeSensitivityChange: (Float) -> Unit,
    groqKeyUsageStats: List<KeyUsageStats>,
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>,
    onSignOut: () -> Unit,
    // AI Memory
    aiMemories: List<com.example.smarty.data.model.AIMemory> = emptyList(),
    onDeleteAIMemory: (com.example.smarty.data.model.AIMemory) -> Unit = {},
    onClearAllAIMemories: () -> Unit = {},
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
    onLoadDeviceCalendars: () -> Unit = {},
    // Local LLM Server
    isLocalPCEnabled: Boolean = false,
    onSetLocalPCEnabled: (Boolean) -> Unit = {},
    localServerIP: String = "",
    localServerPort: String = "1234",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    // Embedded content for sub-sheets
    archiveContent: @Composable (onDismiss: () -> Unit) -> Unit,
    backupContent: @Composable (onDismiss: () -> Unit) -> Unit
) {
    // Handle back button
    BackHandler(enabled = true) {
        onDismiss()
    }

    // Full-page overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        SettingsScreen(
            providerConfigs = providerConfigs,
            providerPriorityOrder = providerPriorityOrder,
            isDarkTheme = isDarkTheme,
            onBackClick = onDismiss,
            onAddApiKey = onAddApiKey,
            onRemoveApiKey = onRemoveApiKey,
            onUpdateApiKey = onUpdateApiKey,
            onSetProviderEnabled = onSetProviderEnabled,
            onSetSelectedModel = onSetSelectedModel,
            onSetProviderPriority = onSetProviderPriority,
            onTestApiKey = onTestApiKey,
            onToggleTheme = onToggleTheme,
            tavilyApiKeys = tavilyApiKeys,
            onAddTavilyApiKey = onAddTavilyApiKey,
            onRemoveTavilyApiKey = onRemoveTavilyApiKey,
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            isClearingCache = isClearingCache,
            shakeSensitivity = shakeSensitivity,
            onShakeSensitivityChange = onShakeSensitivityChange,
            groqKeyUsageStats = groqKeyUsageStats,
            onRefreshModels = onRefreshModels,
            getAvailableModels = getAvailableModels,
            onSignOut = onSignOut,
            archiveContent = archiveContent,
            backupContent = backupContent,
            // AI Memory
            aiMemories = aiMemories,
            onDeleteAIMemory = onDeleteAIMemory,
            onClearAllAIMemories = onClearAllAIMemories,
            onSyncAIMemories = onSyncAIMemories,
            isMemorySyncInProgress = isMemorySyncInProgress,
            memorySyncResult = memorySyncResult,
            unreadForMemoryCount = unreadForMemoryCount,
            onClearMemorySyncResult = onClearMemorySyncResult,
            // Google Calendar Two-Way Sync
            isCalendarSyncEnabled = isCalendarSyncEnabled,
            onSetCalendarSyncEnabled = onSetCalendarSyncEnabled,
            deviceCalendars = deviceCalendars,
            targetCalendarId = targetCalendarId,
            onSetTargetCalendarId = onSetTargetCalendarId,
            onLoadDeviceCalendars = onLoadDeviceCalendars,
            // Local LLM
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
}
