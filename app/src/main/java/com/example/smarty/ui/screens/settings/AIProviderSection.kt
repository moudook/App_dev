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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.local.AIProvider
import com.example.smarty.ui.components.CalmThinkingDots
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow
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

    // Soft Minimalist Card
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .softCardShadow(
                elevation = if (isExpanded) 6.dp else 2.dp,
                shape = RoundedCornerShape(24.dp),
                spotColor = Color.Black.copy(alpha = 0.05f)
            ),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isEnabled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    else Color.Transparent
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Provider header with toggle and expansion
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null // Remove ripple for cleaner feel
                    ) { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Status dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                if (isEnabled && apiKeys.isNotEmpty()) LocalAccentColor.current
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = providerName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                letterSpacing = (-0.5).sp
                            ),
                            color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        AnimatedVisibility(visible = isExpanded) {
                            Text(
                                text = providerDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
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
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = { isExpanded = !isExpanded }, modifier = Modifier.size(32.dp)) {
                         Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .rotate(if (isExpanded) 180f else 0f)
                        )
                    }
                }
            }

            // Content (Keys, Models)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

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
                            text = stringResource(R.string.api_keys),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }

                    // Existing API keys List
                    if (apiKeys.isEmpty() && !showNewKeyInput) {
                        // Minimal empty state
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(16.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.no_keys_configured),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.add_an_api_key_to_enable_features, providerName.lowercase()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

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
                        Surface(
                            onClick = { showNewKeyInput = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (apiKeys.isEmpty()) stringResource(R.string.add_api_key) else stringResource(R.string.add_backup_key),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = selectedDisplayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.model)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAccentColor.current,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                availableModels.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                // Optional: Show model ID in smaller text if needed for clarity
                                if (displayName != modelId) {
                                    Text(
                                        text = modelId,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        },
                        onClick = { onSelectModel(modelId) },
                        leadingIcon = if (modelId == selectedModel) {
                            {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (onRefreshModels != null) {
            IconButton(
                onClick = onRefreshModels,
                modifier = Modifier.size(48.dp) // Match height roughly
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
        usageStats?.healthStatus == KeyHealthStatus.ERROR -> MaterialTheme.colorScheme.error
        usageStats?.healthStatus == KeyHealthStatus.DAILY_EXHAUSTED -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
        usageStats?.healthStatus == KeyHealthStatus.RATE_LIMITED -> MaterialTheme.colorScheme.tertiary
        usageStats?.healthStatus == KeyHealthStatus.COOLDOWN -> MaterialTheme.colorScheme.tertiary
        testResult == true -> LocalAccentColor.current
        testResult == false -> MaterialTheme.colorScheme.error
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
                        text = stringResource(R.string.api_key_label, keyNumber),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Show label if available
                    usageStats?.label?.takeIf { it.isNotBlank() }?.let { label ->
                        Text(
                            text = " ${stringResource(R.string.bullet_separator)} $label",
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
                            CalmThinkingDots(
                                color = LocalAccentColor.current,
                                dotSize = 3.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Build, // Standard build/test icon
                                contentDescription = stringResource(R.string.test),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Edit button
                    if (!isEditing) {
                        IconButton(onClick = onStartEdit, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Edit, // Standard edit icon
                                contentDescription = stringResource(R.string.edit),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    // Delete button
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline, // Standard delete icon
                            contentDescription = stringResource(R.string.remove),
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
            Icons.Default.CheckCircle,
            stringResource(R.string.ok)
        )
        KeyHealthStatus.RATE_LIMITED -> Triple(
            MaterialTheme.colorScheme.tertiary,
            Icons.Default.Schedule,
            stringResource(R.string.rate_limited)
        )
        KeyHealthStatus.DAILY_EXHAUSTED -> Triple(
            MaterialTheme.colorScheme.error,
            Icons.Default.Block,
            stringResource(R.string.daily_limit)
        )
        KeyHealthStatus.ERROR -> Triple(
            MaterialTheme.colorScheme.error,
            Icons.Default.ErrorOutline, // Standard error icon
            stringResource(R.string.error)
        )
        KeyHealthStatus.COOLDOWN -> Triple(
            MaterialTheme.colorScheme.tertiary,
            Icons.Default.HourglassEmpty,
            stringResource(R.string.cooldown)
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
                text = stringResource(R.string.rate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.calls_per_minute, stats.callsThisMinute, stats.rateLimit),
                style = MaterialTheme.typography.labelSmall,
                color = if (stats.callsThisMinute >= stats.rateLimit)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        // Rate limit progress bar
        val rateProgress = (stats.callsThisMinute.toFloat() / stats.rateLimit).coerceIn(0f, 1f)
        com.example.smarty.ui.components.CalmLinearProgress(
            progress = { rateProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = when {
                rateProgress >= 1f -> MaterialTheme.colorScheme.error
                rateProgress >= 0.8f -> MaterialTheme.colorScheme.tertiary
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
                text = stringResource(R.string.today),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.calls_today, stats.callsToday, stats.dailyLimit),
                style = MaterialTheme.typography.labelSmall,
                color = if (stats.callsToday >= stats.dailyLimit)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }

        // Daily progress bar
        val dailyProgress = (stats.callsToday.toFloat() / stats.dailyLimit).coerceIn(0f, 1f)
        com.example.smarty.ui.components.CalmLinearProgress(
            progress = { dailyProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = when {
                dailyProgress >= 1f -> MaterialTheme.colorScheme.error
                dailyProgress >= 0.8f -> MaterialTheme.colorScheme.tertiary
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
        OutlinedTextField(
            value = editValue,
            onValueChange = onEditValueChange,
            modifier = Modifier.weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = MonoFont,
                color = MaterialTheme.colorScheme.onSurface
            ),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LocalAccentColor.current,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            )
        )
        IconButton(onClick = onSaveEdit) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.save),
                tint = LocalAccentColor.current,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onCancelEdit) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
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
            modifier = Modifier
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
        IconButton(onClick = onToggleVisibility) {
            Icon(
                imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = stringResource(if (showKey) R.string.hide else R.string.show),
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
            imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (isValid) LocalAccentColor.current else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = if (isValid) stringResource(R.string.valid_api_key) else stringResource(R.string.invalid_api_key),
            style = MaterialTheme.typography.labelSmall,
            color = if (isValid) LocalAccentColor.current else MaterialTheme.colorScheme.error
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.add_new_api_key),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
            placeholder = {
                Text(
                    text = stringResource(R.string.paste_api_key_here),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = LocalAccentColor.current,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface
            ),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (showKey) R.string.hide else R.string.show),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text(stringResource(R.string.cancel))
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
                Text(stringResource(R.string.add_key))
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
