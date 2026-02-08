package com.example.smarty.ui.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.AIMemory
import com.example.smarty.calendar.GoogleCalendarSyncManager.DeviceCalendar
import com.example.smarty.ui.screens.SettingsScreen

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
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean,
    shakeSensitivity: Float,
    onShakeSensitivityChange: (Float) -> Unit,
    onSignOut: () -> Unit,
    // AI Memory
    aiMemories: List<AIMemory> = emptyList(),
    onDeleteAIMemory: (AIMemory) -> Unit = {},
    onClearAllAIMemories: () -> Unit = {},
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
    onNavigateToCoinToss: () -> Unit = {},
    onNavigateToTicTacToe: () -> Unit = {},
    // Local LLM Server
    isLocalPCEnabled: Boolean = false,
    onSetLocalPCEnabled: (Boolean) -> Unit = {},
    localServerIP: String = "",
    localServerPort: String = "1234",
    localServerUseHttps: Boolean = false,
    onSetLocalServerIP: (String) -> Unit = {},
    onSetLocalServerPort: (String) -> Unit = {},
    onSetLocalServerUseHttps: (Boolean) -> Unit = {},
    onTestLocalServer: (String, String, Boolean, (com.example.smarty.viewmodel.managers.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit = { _, _, _, _ -> },
    // Embedded content for sub-sheets
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
            isDarkTheme = isDarkTheme,
            onBackClick = onDismiss,
            onToggleTheme = onToggleTheme,
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            isClearingCache = isClearingCache,
            shakeSensitivity = shakeSensitivity,
            onShakeSensitivityChange = onShakeSensitivityChange,
            onSignOut = onSignOut,
            backupContent = backupContent,
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
            onSetLocalServerUseHttps = onSetLocalServerUseHttps,
            onTestLocalServer = onTestLocalServer
        )
    }
}
