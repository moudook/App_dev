package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.ShakeSensitivityControl
import com.example.smarty.features.settings.ui.formatCacheSize
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.features.calendar.domain.GoogleCalendarSyncManager.DeviceCalendar

/**
 * Inline settings content with "Chroma Studio" aesthetic.
 * Now features an inline, minimalistic AI Model configuration section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    // Theme
    isDarkTheme: Boolean,
    onToggleTheme: (Boolean) -> Unit,
    // Cache
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    isClearingCache: Boolean,
    // Shake
    shakeSensitivity: Float,
    onShakeSensitivityChange: (Float) -> Unit,
    // Sign out
    onSignOut: () -> Unit,
    // Embedded content for sub-sheets
    backupContent: @Composable (() -> Unit) -> Unit,
    // Content padding
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    // Navigation for Activities
    onNavigateToTicTacToe: () -> Unit = {},
    onNavigateToCoinToss: () -> Unit = {},
    // Google Calendar Two-Way Sync
    isCalendarSyncEnabled: Boolean = false,
    onSetCalendarSyncEnabled: (Boolean) -> Unit = {},
    deviceCalendars: List<DeviceCalendar> = emptyList(),
    targetCalendarId: Long = -1L,
    onSetTargetCalendarId: (Long) -> Unit = {},
    onLoadDeviceCalendars: () -> Unit = {},
    // Remote Server Configuration
    serverUrl: String = "",
    onSetServerUrl: (String) -> Unit = {},
    onTestServerConnection: (String, (com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit = { _, _ -> }
) {
    val accentColor = LocalAccentColor.current
    val context = LocalContext.current
    val shapes = LocalShapes.current

    // Sub-sheet states
    var showBackupSheet by remember { mutableStateOf(false) }
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
    var showCalendarSelectorSheet by remember { mutableStateOf(false) }

    // Inline Expansion States
    val expandedSections = remember { mutableStateMapOf<String, Boolean>() }

    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {

        // --- AI MODELS SECTION (Replaces Pro Banner) ---
        item {
            Column {
                Text(
                    text = stringResource(R.string.ai_models),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )

                SettingsCard {
                    // Remote Server Configuration
                    InlineServerConfig(
                        isExpanded = expandedSections["server"] == true,
                        onExpandChange = { expandedSections["server"] = it },
                        serverUrl = serverUrl,
                        onServerUrlChange = onSetServerUrl,
                        onTestConnection = onTestServerConnection
                    )
                }
            }
        }

        // --- GROUP 1: PERSONALIZATION ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.CloudSync,
                    label = stringResource(R.string.backup),
                    onClick = { showBackupSheet = true },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsSwitch(
                    icon = Icons.Filled.Sync,
                    label = stringResource(R.string.google_calendar_sync),
                    checked = isCalendarSyncEnabled,
                    onCheckedChange = {
                        onSetCalendarSyncEnabled(it)
                        if (it) onLoadDeviceCalendars()
                    },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f),
                    showDivider = isCalendarSyncEnabled
                )
                if (isCalendarSyncEnabled) {
                    val selectedCalendar = deviceCalendars.find { it.id == targetCalendarId }
                    SettingsItem(
                        icon = Icons.Filled.Event,
                        label = stringResource(R.string.default_calendar),
                        value = selectedCalendar?.displayName?.lowercase() ?: stringResource(R.string.select),
                        onClick = {
                            onLoadDeviceCalendars()
                            showCalendarSelectorSheet = true
                        },
                        showDivider = false,
                        iconColor = accentColor,
                        containerColor = accentColor.copy(alpha = 0.1f)
                    )
                }
            }
        }

        // --- GROUP 2: APP SETTINGS ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.Games,
                    label = "Mental Break",
                    onClick = onNavigateToTicTacToe,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Default.Casino,
                    label = "Coin Toss",
                    onClick = onNavigateToCoinToss,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsSwitch(
                    icon = Icons.Default.Brightness4,
                    label = stringResource(R.string.dark_mode),
                    checked = isDarkTheme,
                    onCheckedChange = onToggleTheme,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.Default.Vibration,
                    label = stringResource(R.string.shake_sensitivity),
                    value = if (shakeSensitivity < 0.3f) stringResource(R.string.low) else if (shakeSensitivity < 0.7f) stringResource(R.string.medium) else stringResource(R.string.high),
                    onClick = { showShakeSensitivitySheet = true },
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                SettingsItem(
                    icon = Icons.Default.AutoAwesome,
                    label = stringResource(R.string.default_assistant),
                    onClick = {
                         try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    showDivider = false,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
            }
        }

        // --- GROUP 3: SYSTEM ---
        item {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.clear_cache),
                    value = formatCacheSize(cacheSizeBytes).lowercase(),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0,
                    iconColor = accentColor,
                    containerColor = accentColor.copy(alpha = 0.1f)
                )
                 SettingsItem(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = stringResource(R.string.sign_out),
                    onClick = onSignOut,
                    textColor = Color(0xFFFF3B30),
                    iconColor = Color(0xFFFF3B30),
                    showChevron = false,
                    showDivider = false
                )
            }
        }

        // Bottom Banner
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.smarty_version).lowercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }

    // -- Sheets --
    if (showBackupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBackupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                backupContent { showBackupSheet = false }
            }
        }
    }

    if (showShakeSensitivitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showShakeSensitivitySheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Vibration,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.shake_sensitivity),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.adjust_shake_gesture),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(32.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        ShakeSensitivityControl(sensitivity = shakeSensitivity, onSensitivityChange = onShakeSensitivityChange)
                    }
                }
            }
        }
    }

    if (showCalendarSelectorSheet) {
        ModalBottomSheet(
            onDismissRequest = { showCalendarSelectorSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = shapes.bottomSheet
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 48.dp)) {
                Text(
                    text = stringResource(R.string.select_default_calendar),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(deviceCalendars.size) { index ->
                        val calendar = deviceCalendars[index]
                        val isSelected = calendar.id == targetCalendarId
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { onSetTargetCalendarId(calendar.id); showCalendarSelectorSheet = false },
                            shape = RoundedCornerShape(26.dp),
                            color = if (isSelected) accentColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, accentColor) else null
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(calendar.color?.let { Color(it) } ?: Color.Gray))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(calendar.displayName, style = MaterialTheme.typography.titleMedium, color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface)
                                    Text(calendar.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isSelected) Icon(Icons.Default.Check, null, tint = accentColor, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Inline AI Config Components ---

@Composable
fun InlineServerConfig(
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onTestConnection: (String, (com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult) -> Unit) -> Unit
) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    // Local state
    var currentUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    var isEditing by remember { mutableStateOf(false) }

    // Test state
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult?>(null) }

    // Clear test result after 5 seconds
    LaunchedEffect(testResult) {
        if (testResult != null) {
            kotlinx.coroutines.delay(5000)
            testResult = null
        }
    }

    // Sync from props only if not editing
    LaunchedEffect(serverUrl, isExpanded) {
        if (!isEditing) {
            currentUrl = serverUrl
        }
    }

    Column {
        InlineHeader(
            icon = Icons.Default.Cloud,
            title = "Smarty Server",
            isExpanded = isExpanded,
            onExpandChange = onExpandChange,
            isEnabled = true,
            onEnabledChange = {},
            hasSwitch = false
        )

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                Text(
                    text = "Server URL (Tailscale or local network)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentUrl,
                        onValueChange = {
                            currentUrl = it
                            isEditing = true
                            onServerUrlChange(it)
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("http://100.x.y.z:7860") },
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            cursorColor = MaterialTheme.colorScheme.primary
                        ),
                        trailingIcon = {
                             if (currentUrl.isNotEmpty()) {
                                 IconButton(onClick = {
                                     currentUrl = ""
                                     isEditing = true
                                     onServerUrlChange("")
                                 }) {
                                     Icon(Icons.Default.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                 }
                             }
                        }
                    )

                    // Paste Button
                    IconButton(
                        onClick = {
                            val clipboardText = clipboardManager.getText()?.toString()
                            if (!clipboardText.isNullOrBlank()) {
                                currentUrl = clipboardText
                                isEditing = true
                                onServerUrlChange(clipboardText)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Icon(Icons.Default.ContentPaste, "Paste", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))

                    // Test Button
                    Button(
                        onClick = {
                            if (currentUrl.isNotBlank()) {
                                isTesting = true
                                testResult = null
                                onTestConnection(currentUrl.trim()) { result ->
                                    isTesting = false
                                    testResult = result
                                }
                            }
                        },
                        enabled = currentUrl.isNotBlank() && !isTesting,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                         if (isTesting) {
                             CircularProgressIndicator(
                                 modifier = Modifier.size(16.dp),
                                 color = MaterialTheme.colorScheme.onPrimary,
                                 strokeWidth = 2.dp
                             )
                         } else {
                             Text("Test Connection")
                         }
                    }
                }

                // Test Result Message
                AnimatedVisibility(visible = testResult != null) {
                    val result = testResult
                    if (result != null) {
                        val isSuccess = result is com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult.Success
                        val message = if (isSuccess) "Connected successfully!" else (result as com.example.smarty.features.settings.domain.SettingsFeatureManager.LocalServerTestResult.Failure).message

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSuccess) Color(0xFFE8F5E9) else Color(0xFFFFEBEE))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSuccess) Color(0xFF1B5E20) else Color(0xFFB71C1C)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InlineHeader(
    icon: ImageVector,
    title: String,
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    hasSwitch: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandChange(!isExpanded) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        if (hasSwitch) {
            Switch(
                checked = isEnabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.scale(0.8f)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Icon(
            imageVector = if(isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Components from previous file needed for layout
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).softCardShadow(elevation = 4.dp, shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp), content = content)
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String? = null,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Transparent,
    showChevron: Boolean = true,
    showDivider: Boolean = false,
    enabled: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (enabled) iconColor else iconColor.copy(alpha = 0.4f), modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = if (enabled) textColor else textColor.copy(alpha = 0.4f), modifier = Modifier.weight(1f))
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            if (showChevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 78.dp, end = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
    }
}

@Composable
private fun SettingsSwitch(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconColor: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Transparent,
    showDivider: Boolean = false,
    accentColor: Color = LocalAccentColor.current
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = accentColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    uncheckedBorderColor = Color.Transparent
                ),
                modifier = Modifier.scale(0.8f)
            )
        }
        if (showDivider) HorizontalDivider(modifier = Modifier.padding(start = 78.dp, end = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))
    }
}

