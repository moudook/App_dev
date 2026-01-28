package com.example.smarty.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smarty.data.local.AIProvider
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.SafetyOrange
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.util.api.KeyHealthStatus

/**
 * AI provider configuration section for settings.
 *
 * Displays provider information, API key management, and model selection.
 * Supports multiple API keys with primary/backup designation.
 *
 * @param provider The AI provider enum value
 * @param providerName Display name for the provider
 * @param providerDescription Short description of the provider
 * @param apiKeys List of configured API keys
 * @param isEnabled Whether this provider is enabled
 * @param selectedModel Currently selected model ID
 * @param availableModels List of available models (id to displayName pairs)
 * @param onAddKey Callback when adding a new API key
 * @param onRemoveKey Callback when removing an API key
 * @param onUpdateKey Callback when updating an API key (old, new)
 * @param onToggleEnabled Callback when toggling provider enabled state
 * @param onSelectModel Callback when selecting a model
 * @param onTestKey Callback to test an API key validity
 * @param keyUsageStats Map of API key to usage statistics (for GROQ keys)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSection(
    provider: AIProvider,
    providerName: String,
    providerDescription: String,
    apiKeys: List<String>,
    isEnabled: Boolean,
    selectedModel: String,
    availableModels: List<Pair<String, String>>,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onUpdateKey: (String, String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onTestKey: (String, (Boolean) -> Unit) -> Unit,
    keyUsageStats: Map<String, KeyUsageStats> = emptyMap(),
    onRefreshModels: (() -> Unit)? = null
) {
    var newKeyInput by remember { mutableStateOf("") }
    var showNewKeyInput by remember { mutableStateOf(false) }
    var editingKeyIndex by remember { mutableStateOf(-1) }
    var editingKeyValue by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    
    // Decoupled expansion state - default expand if enabled and keys exist, or if it's the first one
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Provider header with toggle and expansion
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Column {
                        Text(
                            text = providerName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = providerDescription,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                     Switch(
                        checked = isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = LocalAccentColor.current,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            uncheckedBorderColor = Color.Transparent
                        ),
                        modifier = Modifier.scale(0.8f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(28.dp)) {
                         Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Content (Keys, Models)
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // Model selector - only shown when API keys are configured and enabled
                    AnimatedVisibility(
                        visible = apiKeys.isNotEmpty() && isEnabled,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        ModelSelector(
                            selectedModel = selectedModel,
                            availableModels = availableModels,
                            expanded = modelDropdownExpanded,
                            onExpandedChange = { modelDropdownExpanded = it },
                            onSelectModel = {
                                onSelectModel(it)
                                modelDropdownExpanded = false
                            },
                            onRefreshModels = onRefreshModels
                        )
                    }

                    // Existing API keys Title
                    if (apiKeys.isNotEmpty()) {
                         Text(
                            text = "API KEYS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    // Existing API keys List
                    apiKeys.forEachIndexed { index, key ->
                        ApiKeyItem(
                            apiKey = key,
                            keyNumber = index + 1,
                            isEditing = editingKeyIndex == index,
                            editValue = if (editingKeyIndex == index) editingKeyValue else key,
                            onEditValueChange = { editingKeyValue = it },
                            onStartEdit = {
                                editingKeyIndex = index
                                editingKeyValue = key
                            },
                            onSaveEdit = {
                                if (editingKeyValue.isNotBlank() && editingKeyValue != key) {
                                    onUpdateKey(key, editingKeyValue)
                                }
                                editingKeyIndex = -1
                                editingKeyValue = ""
                            },
                            onCancelEdit = {
                                editingKeyIndex = -1
                                editingKeyValue = ""
                            },
                            onRemove = { onRemoveKey(key) },
                            onTest = { callback -> onTestKey(key, callback) },
                            usageStats = keyUsageStats[key]  // Pass usage stats for this key
                        )
                    }

                    // Add new key input
                    AnimatedVisibility(
                        visible = showNewKeyInput,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        NewApiKeyInput(
                            value = newKeyInput,
                            onValueChange = { newKeyInput = it },
                            onSave = {
                                if (newKeyInput.isNotBlank()) {
                                    onAddKey(newKeyInput)
                                    newKeyInput = ""
                                    showNewKeyInput = false
                                }
                            },
                            onCancel = {
                                newKeyInput = ""
                                showNewKeyInput = false
                            }
                        )
                    }

                    // Add key button
                    if (!showNewKeyInput) {
                        Button(
                            onClick = { showNewKeyInput = true },
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (apiKeys.isEmpty()) "Add API Key" else "Add Backup Key")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Model selector dropdown for AI provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelector(
    selectedModel: String,
    availableModels: List<Pair<String, String>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onRefreshModels: (() -> Unit)? = null
) {
    val selectedDisplayName = availableModels.find { it.first == selectedModel }?.second ?: selectedModel

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                border = if (expanded) BorderStroke(1.dp, LocalAccentColor.current) else null,
                onClick = { onExpandedChange(!expanded) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = selectedDisplayName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                         if (onRefreshModels != null) {
                            IconButton(
                                onClick = onRefreshModels,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(if (expanded) 180f else 0f)
                        )
                    }
                }
            }
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                availableModels.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = { onSelectModel(modelId) },
                        leadingIcon = {
                            if (modelId == selectedModel) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = null,
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual API key item with view/edit/test/delete capabilities and usage stats.
 *
 * @param apiKey The API key value
 * @param keyNumber The key number (1 = primary, 2+ = backup)
 * @param isEditing Whether this key is currently being edited
 * @param editValue The current edit value
 * @param onEditValueChange Callback when edit value changes
 * @param onStartEdit Callback to start editing
 * @param onSaveEdit Callback to save the edit
 * @param onCancelEdit Callback to cancel editing
 * @param onRemove Callback to remove this key
 * @param onTest Callback to test this key
 * @param usageStats Optional usage statistics for this key
 */
@Composable
fun ApiKeyItem(
    apiKey: String,
    keyNumber: Int,
    isEditing: Boolean,
    editValue: String,
    onEditValueChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onRemove: () -> Unit,
    onTest: ((Boolean) -> Unit) -> Unit,
    usageStats: KeyUsageStats? = null
) {
    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    // Determine border color based on health status
    // Only show border if there is an issue or active test result
    val showBorder = usageStats?.healthStatus != KeyHealthStatus.HEALTHY && usageStats?.healthStatus != null || testResult != null
    
    val borderColor = when {
        usageStats?.healthStatus == KeyHealthStatus.ERROR -> SafetyOrange
        usageStats?.healthStatus == KeyHealthStatus.DAILY_EXHAUSTED -> SafetyOrange.copy(alpha = 0.7f)
        usageStats?.healthStatus == KeyHealthStatus.RATE_LIMITED -> Color(0xFFFF9800)
        usageStats?.healthStatus == KeyHealthStatus.COOLDOWN -> Color(0xFFFF9800)
        testResult == true -> LocalAccentColor.current
        testResult == false -> SafetyOrange
        else -> Color.Transparent
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (showBorder) Modifier.border(1.dp, borderColor, RoundedCornerShape(12.dp)) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row with key number, label, and action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Key #$keyNumber",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Show label if available
                    usageStats?.label?.takeIf { it.isNotBlank() }?.let { label ->
                        Text(
                            text = " • $label",
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalAccentColor.current
                        )
                    }
                    // Health status indicator
                    usageStats?.let { stats ->
                        Spacer(modifier = Modifier.width(8.dp))
                        KeyHealthBadge(stats.healthStatus)
                    }
                }

                Row {
                    // Test button
                    IconButton(
                        onClick = {
                            isTesting = true
                            testResult = null
                            onTest { result ->
                                isTesting = false
                                testResult = result
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = LocalAccentColor.current
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Science, // Creative: Experiment/Test
                                contentDescription = "Test",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Edit button
                    if (!isEditing) {
                        IconButton(onClick = onStartEdit, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.DesignServices, // Creative: Design/Edit
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Delete button
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Whatshot, // Creative: Fire/Delete
                            contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Key value display/edit
            if (isEditing) {
                ApiKeyEditMode(
                    editValue = editValue,
                    onEditValueChange = onEditValueChange,
                    onSaveEdit = onSaveEdit,
                    onCancelEdit = onCancelEdit
                )
            } else {
                ApiKeyViewMode(
                    apiKey = apiKey,
                    showKey = showKey,
                    onToggleVisibility = { showKey = !showKey }
                )
            }

            // Usage stats display (for GROQ keys)
            usageStats?.let { stats ->
                Spacer(modifier = Modifier.height(12.dp))
                KeyUsageDisplay(stats)
            }

            // Test result
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                TestResultIndicator(isValid = result)
            }
        }
    }
}

/**
 * Health status badge for API key.
 */
@Composable
private fun KeyHealthBadge(status: KeyHealthStatus) {
    val (color, icon, text) = when (status) {
        KeyHealthStatus.HEALTHY -> Triple(
            LocalAccentColor.current,
            Icons.Default.Verified,
            "OK"
        )
        KeyHealthStatus.RATE_LIMITED -> Triple(
            Color(0xFFFF9800),
            Icons.Default.Schedule,
            "Rate Limited"
        )
        KeyHealthStatus.DAILY_EXHAUSTED -> Triple(
            SafetyOrange,
            Icons.Default.Block,
            "Daily Limit"
        )
        KeyHealthStatus.ERROR -> Triple(
            SafetyOrange,
            Icons.Default.Shield, // Creative: Shield/Protection
            "Error"
        )
        KeyHealthStatus.COOLDOWN -> Triple(
            Color(0xFFFF9800),
            Icons.Default.HourglassEmpty,
            "Cooldown"
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * Usage statistics display for an API key.
 */
@Composable
private fun KeyUsageDisplay(stats: KeyUsageStats) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(8.dp)
            )
            .padding(8.dp)
    ) {
        // Rate limit progress (calls this minute)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Rate",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${stats.callsThisMinute}/${stats.rateLimit}/min",
                style = MaterialTheme.typography.labelSmall,
                color = if (stats.callsThisMinute >= stats.rateLimit)
                    SafetyOrange else MaterialTheme.colorScheme.onSurface
            )
        }

        // Rate limit progress bar
        val rateProgress = (stats.callsThisMinute.toFloat() / stats.rateLimit).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { rateProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = when {
                rateProgress >= 1f -> SafetyOrange
                rateProgress >= 0.8f -> Color(0xFFFF9800)
                else -> LocalAccentColor.current
            },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Daily usage progress
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "${stats.callsToday}/${stats.dailyLimit}",
                style = MaterialTheme.typography.labelSmall,
                color = if (stats.callsToday >= stats.dailyLimit)
                    SafetyOrange else MaterialTheme.colorScheme.onSurface
            )
        }

        // Daily progress bar
        val dailyProgress = (stats.callsToday.toFloat() / stats.dailyLimit).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { dailyProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = when {
                dailyProgress >= 1f -> SafetyOrange
                dailyProgress >= 0.8f -> Color(0xFFFF9800)
                else -> LocalAccentColor.current
            },
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    }
}

/**
 * Edit mode for API key.
 */
@Composable
private fun ApiKeyEditMode(
    editValue: String,
    onEditValueChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = editValue,
            onValueChange = onEditValueChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(LocalAccentColor.current),
            singleLine = true
        )
        IconButton(onClick = onSaveEdit) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = "Save",
                tint = LocalAccentColor.current,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onCancelEdit) {
            Icon(
                imageVector = Icons.Default.HighlightOff,
                contentDescription = "Cancel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * View mode for API key with visibility toggle.
 */
@Composable
private fun ApiKeyViewMode(
    apiKey: String,
    showKey: Boolean,
    onToggleVisibility: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (showKey) apiKey else maskApiKey(apiKey),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToggleVisibility) {
            Icon(
                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = if (showKey) "Hide" else "Show",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * Test result indicator showing valid/invalid status.
 */
@Composable
private fun TestResultIndicator(isValid: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(
            imageVector = if (isValid) Icons.Default.Verified else Icons.Default.GppBad,
            contentDescription = null,
            tint = if (isValid) LocalAccentColor.current else SafetyOrange,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = if (isValid) "Valid API key" else "Invalid API key",
            style = MaterialTheme.typography.labelSmall,
            color = if (isValid) LocalAccentColor.current else SafetyOrange
        )
    }
}

/**
 * Input field for adding a new API key.
 *
 * @param value Current input value
 * @param onValueChange Callback when value changes
 * @param onSave Callback to save the new key
 * @param onCancel Callback to cancel adding
 */
@Composable
fun NewApiKeyInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }

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
            Text(
                text = "New API Key",
                style = MaterialTheme.typography.labelSmall,
                color = LocalAccentColor.current
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = MonoFont,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        singleLine = true,
                        visualTransformation = if (showKey)
                            VisualTransformation.None
                        else
                            PasswordVisualTransformation()
                    )
                    if (value.isEmpty()) {
                        Text(
                            text = "Paste your API key here...",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }

                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) "Hide" else "Show",
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
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    enabled = value.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("Add Key")
                }
            }
        }
    }
}

/**
 * Mask an API key for secure display.
 *
 * Shows the first 4 and last 4 characters, masking the middle.
 *
 * @param key The API key to mask
 * @return Masked key string
 */
fun maskApiKey(key: String): String {
    return if (key.length > 8) {
        "${key.take(4)}${"*".repeat(key.length - 8)}${key.takeLast(4)}"
    } else {
        "*".repeat(key.length)
    }
}
