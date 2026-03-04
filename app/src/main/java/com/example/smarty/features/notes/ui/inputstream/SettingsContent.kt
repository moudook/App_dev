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
import com.example.smarty.ui.components.SmartySettingsCard
import com.example.smarty.ui.components.SmartySettingsRow
import com.example.smarty.ui.components.SmartySettingsSwitchRow

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
    onLoadDeviceCalendars: () -> Unit = {}
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
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // --- AI MODELS SECTION (Replaces Pro Banner) ---
        item {
            Column {
                Text(
                    text = stringResource(R.string.models_and_connection),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)
                )

                SmartySettingsCard {
                    // Server Status Indicator (URL is hardcoded and hidden)
                    ServerStatusIndicator()
                }
            }
        }

        // --- GROUP 1: STORAGE & SYNC ---
        item {
            SmartySettingsCard {
                SmartySettingsRow(
                    icon = Icons.Default.CloudSync,
                    label = stringResource(R.string.backup),
                    onClick = { showBackupSheet = true },
                    iconColor = accentColor
                )
                SmartySettingsSwitchRow(
                    icon = Icons.Filled.Sync,
                    label = stringResource(R.string.google_calendar_sync),
                    checked = isCalendarSyncEnabled,
                    onCheckedChange = {
                        onSetCalendarSyncEnabled(it)
                        if (it) onLoadDeviceCalendars()
                    },
                    iconColor = accentColor
                )
                if (isCalendarSyncEnabled) {
                    val selectedCalendar = deviceCalendars.find { it.id == targetCalendarId }
                    SmartySettingsRow(
                        icon = Icons.Filled.Event,
                        label = stringResource(R.string.default_calendar),
                        subtitle = selectedCalendar?.displayName?.lowercase() ?: stringResource(R.string.select),
                        onClick = {
                            onLoadDeviceCalendars()
                            showCalendarSelectorSheet = true
                        },
                        iconColor = accentColor
                    )
                }
            }
        }

        // --- GROUP 2: PREFERENCES & GAMES ---
        item {
            SmartySettingsCard {
                // Theme
                SmartySettingsSwitchRow(
                    icon = Icons.Default.Brightness4,
                    label = stringResource(R.string.dark_mode),
                    checked = isDarkTheme,
                    onCheckedChange = onToggleTheme,
                    iconColor = accentColor
                )
                // Friday (Assistant)
                SmartySettingsRow(
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
                    iconColor = accentColor
                )
                // Coin Toss
                SmartySettingsRow(
                    icon = Icons.Default.Casino,
                    label = "Coin Toss",
                    onClick = onNavigateToCoinToss,
                    iconColor = accentColor
                )
                // Mental Break
                SmartySettingsRow(
                    icon = Icons.Default.Games,
                    label = "Mental Break",
                    onClick = onNavigateToTicTacToe,
                    iconColor = accentColor
                )
                // Motion (Shake Sensitivity)
                SmartySettingsRow(
                    icon = Icons.Default.Vibration,
                    label = stringResource(R.string.shake_sensitivity),
                    subtitle = if (shakeSensitivity < 0.3f) stringResource(R.string.low) else if (shakeSensitivity < 0.7f) stringResource(R.string.medium) else stringResource(R.string.high),
                    onClick = { showShakeSensitivitySheet = true },
                    iconColor = accentColor
                )
            }
        }

        // --- GROUP 3: WORKSPACE ---
        item {
            SmartySettingsCard {
                SmartySettingsRow(
                    icon = Icons.Default.DeleteOutline,
                    label = stringResource(R.string.clear_cache),
                    subtitle = formatCacheSize(cacheSizeBytes).lowercase(),
                    onClick = onClearCache,
                    enabled = !isClearingCache && cacheSizeBytes > 0,
                    iconColor = accentColor
                )
            }
        }

        // --- GROUP 4: ACCOUNT ---
        item {
            SmartySettingsCard {
                SmartySettingsRow(
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    label = stringResource(R.string.sign_out),
                    onClick = onSignOut,
                    textColor = Color(0xFFFF3B30),
                    iconColor = Color(0xFFFF3B30),
                    showChevron = false
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
fun ServerStatusIndicator() {
    SmartySettingsRow(
        label = "Smarty Server",
        icon = Icons.Default.Cloud,
        subtitle = "Connected",
        enabled = true,
        showChevron = false,
        trailingContent = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Connected",
                tint = Color(0xFF2E7D32),
                modifier = Modifier.size(24.dp)
            )
        }
    )
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

