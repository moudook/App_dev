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
    isPinConfigured: Boolean,
    isDarkTheme: Boolean,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onRemovePin: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    tavilyApiKey: String?,
    onSetTavilyApiKey: (String?) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean,
    shakeSensitivity: Float,
    onShakeSensitivityChange: (Float) -> Unit,
    groqKeyUsageStats: List<KeyUsageStats>,
    isVoiceEnrolled: Boolean,
    onDeleteVoiceFingerprint: () -> Unit,
    onRetrainVoice: () -> Unit,
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>,
    onSignOut: () -> Unit,
    // Embedded content for sub-sheets
    archiveContent: @Composable (onDismiss: () -> Unit) -> Unit,
    backupContent: @Composable (onDismiss: () -> Unit) -> Unit,
    pinSetupContent: @Composable (onDismiss: () -> Unit) -> Unit,
    pinChangeContent: @Composable (onDismiss: () -> Unit) -> Unit
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
            isPinConfigured = isPinConfigured,
            isDarkTheme = isDarkTheme,
            onBackClick = onDismiss,
            onAddApiKey = onAddApiKey,
            onRemoveApiKey = onRemoveApiKey,
            onUpdateApiKey = onUpdateApiKey,
            onSetProviderEnabled = onSetProviderEnabled,
            onSetSelectedModel = onSetSelectedModel,
            onSetProviderPriority = onSetProviderPriority,
            onTestApiKey = onTestApiKey,
            onRemovePin = onRemovePin,
            onToggleTheme = onToggleTheme,
            tavilyApiKey = tavilyApiKey,
            onSetTavilyApiKey = onSetTavilyApiKey,
            cacheSizeBytes = cacheSizeBytes,
            onClearCache = onClearCache,
            isClearingCache = isClearingCache,
            shakeSensitivity = shakeSensitivity,
            onShakeSensitivityChange = onShakeSensitivityChange,
            groqKeyUsageStats = groqKeyUsageStats,
            isVoiceEnrolled = isVoiceEnrolled,
            onDeleteVoiceFingerprint = onDeleteVoiceFingerprint,
            onRetrainVoice = onRetrainVoice,
            onRefreshModels = onRefreshModels,
            getAvailableModels = getAvailableModels,
            onSignOut = onSignOut,
            archiveContent = archiveContent,
            backupContent = backupContent,
            pinSetupContent = pinSetupContent,
            pinChangeContent = pinChangeContent
        )
    }
}
