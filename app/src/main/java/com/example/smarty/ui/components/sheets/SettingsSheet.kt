package com.example.smarty.ui.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager.DeviceCalendar
import com.example.smarty.features.settings.ui.SettingsScreen

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
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {},
    onNavigateToCoinToss: () -> Unit = {},
    onNavigateToTicTacToe: () -> Unit = {},
    // Remote Server
    serverUrl: String = "",
    onSetServerUrl: (String) -> Unit = {},
    onTestServerConnection: (String, (com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit = { _, _ -> },
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
            // Google Calendar Two-Way Sync
            isCalendarSyncEnabled = isCalendarSyncEnabled,
            onSetCalendarSyncEnabled = onSetCalendarSyncEnabled,
            deviceCalendars = deviceCalendars,
            targetCalendarId = targetCalendarId,
            onSetTargetCalendarId = onSetTargetCalendarId,
            onLoadDeviceCalendars = onLoadDeviceCalendars,
            // Remote Server
            serverUrl = serverUrl,
            onSetServerUrl = onSetServerUrl,
            onTestServerConnection = onTestServerConnection,
            onNavigateToCoinToss = onNavigateToCoinToss,
            onNavigateToTicTacToe = onNavigateToTicTacToe
        )
    }
}


