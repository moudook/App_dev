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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.local.AIProvider
import com.example.smarty.ui.components.OrganicThinkingIndicator
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.components.CalmLinearProgress
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
    onRefreshModels: (() -> Unit)? = null,
    iconOverride: ImageVector? = null  // Optional icon override for non-standard providers
) {
    // Unified state for the Control Deck
    var selectedKeyIndex by remember { mutableStateOf(0) }
    var newKeyInput by remember { mutableStateOf("") }
    var showNewKeyInput by remember { mutableStateOf(false) }
    var editingKeyIndex by remember { mutableStateOf(-1) }
    var editingKeyValue by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }

    // Soft Minimalist Card
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .softCardShadow(
                elevation = if (isExpanded) 8.dp else 2.dp,
                shape = RoundedCornerShape(26.dp),
                spotColor = Color.Black.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // DASHBOARD HEADER & ACTIVATION (Remains the tactile entry)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null
                    ) { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = providerName.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        ),
                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = providerDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                ActivationControl(
                    isEnabled = isEnabled,
                    onToggle = onToggleEnabled,
                    providerIcon = iconOverride ?: when (provider) {
                        AIProvider.OPENAI -> Icons.Default.Assistant
                        AIProvider.ANTHROPIC -> Icons.Default.Waves
                        AIProvider.GROQ -> Icons.Default.FlashOn
                        else -> Icons.Default.SmartToy
                    }
                )
            }

            // CENTRALIZED CONTROL DECK (Reveals when expanded)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))

                    // 1. ENGINE HUB (Central Model Selection)
                    if (isEnabled) {
                        EngineHub(
                            selectedModel = selectedModel,
                            availableModels = availableModels,
                            onSelectModel = onSelectModel,
                            onRefreshModels = onRefreshModels
                        )
                    }

                    // 2. POWER BLADE MATRIX (API Keys - Compact Grid)
                    PowerBladeMatrix(
                        apiKeys = apiKeys,
                        selectedIndex = selectedKeyIndex,
                        onSelectIndex = { selectedKeyIndex = it },
                        onAddKeyRequested = { showNewKeyInput = true },
                        keyUsageStats = keyUsageStats
                    )

                    // 3. CENTRAL DIAGNOSTIC HUD (Details for selected key)
                    if (apiKeys.isNotEmpty()) {
                        val activeKey = apiKeys.getOrNull(selectedKeyIndex) ?: apiKeys.first()
                        DiagnosticHUD(
                            apiKey = activeKey,
                            keyNumber = selectedKeyIndex + 1,
                            isEditing = editingKeyIndex == selectedKeyIndex,
                            editValue = editingKeyValue,
                            onEditValueChange = { editingKeyValue = it },
                            onStartEdit = {
                                editingKeyIndex = selectedKeyIndex
                                editingKeyValue = activeKey
                            },
                            onSaveEdit = {
                                if (editingKeyValue.isNotBlank() && editingKeyValue != activeKey) {
                                    onUpdateKey(activeKey, editingKeyValue)
                                }
                                editingKeyIndex = -1
                            },
                            onCancelEdit = { editingKeyIndex = -1 },
                            onRemove = {
                                onRemoveKey(activeKey)
                                selectedKeyIndex = (selectedKeyIndex - 1).coerceAtLeast(0)
                            },
                            onTest = { callback -> onTestKey(activeKey, callback) },
                            usageStats = keyUsageStats[activeKey]
                        )
                    }

                    // Inline New Key Input
                    AnimatedVisibility(visible = showNewKeyInput) {
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
                }
            }
        }
    }
}

/**
 * Centered Engine Hub for Model Selection
 */
@Composable
private fun EngineHub(
    selectedModel: String,
    availableModels: List<Pair<String, String>>,
    onSelectModel: (String) -> Unit,
    onRefreshModels: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ACTIVE ENGINE",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            
            if (onRefreshModels != null) {
                IconButton(onClick = onRefreshModels, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }

        ModelSelector(
            selectedModel = selectedModel,
            availableModels = availableModels,
            onSelectModel = onSelectModel,
            onRefreshModels = onRefreshModels,
            labelOverride = "SELECT PRIMARY ENGINE"
        )
    }
}

/**
 * Compact Blade Matrix for API Keys
 */
@Composable
private fun PowerBladeMatrix(
    apiKeys: List<String>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    onAddKeyRequested: () -> Unit,
    keyUsageStats: Map<String, KeyUsageStats>
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "POWER BLADES",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            
            IconButton(onClick = onAddKeyRequested, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Add, null, tint = Color(0xFF007AFF), modifier = Modifier.size(16.dp))
            }
        }

        // Horizontal scrollable blades for compactness
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            apiKeys.forEachIndexed { index, key ->
                val stats = keyUsageStats[key]
                val isSelected = index == selectedIndex

                PowerBlade(
                    label = "CELL ${index + 1}",
                    isSelected = isSelected,
                    onClick = { onSelectIndex(index) },
                    healthStatus = stats?.healthStatus ?: KeyHealthStatus.HEALTHY
                )
            }
        }
    }
}

/**
 * Compact Blade Component
 */
@Composable
private fun PowerBlade(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    healthStatus: KeyHealthStatus
) {
    val statusColor = when (healthStatus) {
        KeyHealthStatus.HEALTHY -> Color(0xFF34C759)
        KeyHealthStatus.ERROR, KeyHealthStatus.DAILY_EXHAUSTED -> Color(0xFFFF3B30)
        else -> Color(0xFF007AFF)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f) else Color.Transparent,
        border = BorderStroke(
            1.dp, 
            if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f) 
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status LED
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Shared Diagnostic HUD for centralized details
 */
@Composable
private fun DiagnosticHUD(
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

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // HUD Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "BLADE ${keyNumber} DIAGNOSTICS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (usageStats != null) KeyHealthBadge(usageStats.healthStatus)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        modifier = Modifier.size(28.dp)
                    ) {
                        if (isTesting) OrganicThinkingIndicator(baseColor = Color(0xFF007AFF), size = 18.dp)
                        else Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    }
                    if (!isEditing) {
                        IconButton(onClick = onStartEdit, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        }
                    }
                    IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFFF3B30).copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Centralized Value Area
            if (isEditing) {
                ApiKeyEditMode(editValue, onEditValueChange, onSaveEdit, onCancelEdit)
            } else {
                ApiKeyViewMode(apiKey, showKey) { showKey = !showKey }
            }

            // Unified Stats Area
            if (usageStats != null) {
                KeyUsageDisplay(usageStats)
            } else if (testResult != null) {
                TestResultIndicator(isValid = testResult!!)
            } else {
                Text(
                    text = "NO DIAGNOSTIC DATA AVAILABLE FOR THIS BLADE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Tactile Activation Control for Power Hub
 */
@Composable
private fun ActivationControl(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    providerIcon: ImageVector
) {
    Surface(
        onClick = { onToggle(!isEnabled) },
        shape = RoundedCornerShape(16.dp),
        color = if (isEnabled) Color(0xFF007AFF).copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(
            0.5.dp, 
            if (isEnabled) Color(0xFF007AFF).copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = providerIcon,
                contentDescription = null,
                tint = if (isEnabled) Color(0xFF007AFF) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
            
            Text(
                text = if (isEnabled) "POWER ON" else "OFFLINE",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (isEnabled) Color(0xFF007AFF) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            
            // Minimal Power Lamp
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isEnabled) Color(0xFF007AFF) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
            )
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
    onSelectModel: (String) -> Unit,
    onRefreshModels: (() -> Unit)? = null,
    labelOverride: String? = null
) {
    val selectedDisplayName = availableModels.find { it.first == selectedModel }?.second ?: selectedModel
    var expanded by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = selectedDisplayName,
                onValueChange = {},
                readOnly = true,
                label = { Text(labelOverride ?: stringResource(R.string.model)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
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
                                    tint = Color(0xFF34C759), // Semantic Green
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
 * Individual API key item is now handled by PowerBlade and DiagnosticHUD
 */

/**
 * Health status badge for API key.
 */
@Composable
private fun KeyHealthBadge(status: KeyHealthStatus) {
    val (color, icon, text) = when (status) {
        KeyHealthStatus.HEALTHY -> Triple(
            Color(0xFF34C759), // Semantic Green
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
        CalmLinearProgress(
            progress = { rateProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = when {
                rateProgress >= 1f -> Color(0xFFFF3B30) // Semantic Red
                rateProgress >= 0.8f -> Color(0xFF007AFF) // Semantic Blue (Warning)
                else -> Color(0xFF007AFF) // Semantic Blue
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
        CalmLinearProgress(
            progress = { dailyProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = when {
                dailyProgress >= 1f -> Color(0xFFFF3B30) // Semantic Red
                dailyProgress >= 0.8f -> Color(0xFF007AFF) // Semantic Blue
                else -> Color(0xFF007AFF) // Semantic Blue
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
                focusedBorderColor = Color.White,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
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
