package com.example.smarty.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smarty.data.local.AIProvider
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.SafetyOrange

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
    onTestKey: (String, (Boolean) -> Unit) -> Unit
) {
    var newKeyInput by remember { mutableStateOf("") }
    var showNewKeyInput by remember { mutableStateOf(false) }
    var editingKeyIndex by remember { mutableStateOf(-1) }
    var editingKeyValue by remember { mutableStateOf("") }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider header with toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isEnabled && apiKeys.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = providerDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LocalAccentColor.current,
                    checkedTrackColor = LocalAccentColor.current.copy(alpha = 0.3f)
                )
            )
        }

        // Content when enabled
        AnimatedVisibility(
            visible = isEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Model selector - only shown when API keys are configured
                AnimatedVisibility(
                    visible = apiKeys.isNotEmpty(),
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
                        }
                    )
                }

                // Existing API keys
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
                        onTest = { callback -> onTestKey(key, callback) }
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
                    OutlinedButton(
                        onClick = { showNewKeyInput = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(ComponentSpacing.iconSize)
                        )
                        Spacer(modifier = Modifier.width(ComponentSpacing.iconGap))
                        Text(if (apiKeys.isEmpty()) "Add API Key" else "Add Backup Key")
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
    onSelectModel: (String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Model",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = onExpandedChange
        ) {
            OutlinedTextField(
                value = availableModels.find { it.first == selectedModel }?.second ?: selectedModel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LocalAccentColor.current,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) }
            ) {
                availableModels.forEach { (modelId, displayName) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        onClick = { onSelectModel(modelId) },
                        leadingIcon = {
                            if (modelId == selectedModel) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = LocalAccentColor.current,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual API key item with view/edit/test/delete capabilities.
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
    onTest: ((Boolean) -> Unit) -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                when {
                    testResult == true -> LocalAccentColor.current
                    testResult == false -> SafetyOrange
                    else -> MaterialTheme.colorScheme.outline
                },
                RoundedCornerShape(ComponentSpacing.inputCornerRadius)
            ),
        shape = RoundedCornerShape(ComponentSpacing.inputCornerRadius),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row with key number and action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Key #$keyNumber${if (keyNumber == 1) " (Primary)" else " (Backup)"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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
                        enabled = !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = LocalAccentColor.current
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Test",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Edit button
                    if (!isEditing) {
                        IconButton(onClick = onStartEdit) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Delete button
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Remove",
                            tint = SafetyOrange,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

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

            // Test result
            testResult?.let { result ->
                TestResultIndicator(isValid = result)
            }
        }
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
                imageVector = Icons.Default.Check,
                contentDescription = "Save",
                tint = LocalAccentColor.current,
                modifier = Modifier.size(18.dp)
            )
        }
        IconButton(onClick = onCancelEdit) {
            Icon(
                imageVector = Icons.Default.Close,
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
            imageVector = if (isValid) Icons.Default.CheckCircle else Icons.Default.Error,
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
