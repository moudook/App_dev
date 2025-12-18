package com.example.smarty.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.screens.settings.ProviderSection
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Duolingo-style Settings Screen
 * Clean, simple, minimal scrolling required
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    isPinConfigured: Boolean,
    isDarkTheme: Boolean,
    onBackClick: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onRemovePin: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    // Embedded Content Slots
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    pinSetupContent: @Composable (() -> Unit) -> Unit,
    pinChangeContent: @Composable (() -> Unit) -> Unit,
    lastBackupTime: Long = 0L,
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showRemovePinDialog by remember { mutableStateOf(false) }
    var showAIConfigSheet by remember { mutableStateOf(false) }
    val aiConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Bottom Sheets for Sub-settings
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showPinSetupSheet by remember { mutableStateOf(false) }
    var showPinChangeSheet by remember { mutableStateOf(false) }
    val subSettingSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isSystemDark = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onToggleTheme(!isDarkTheme) }) {
                        Icon(
                            imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {


            // Main Settings List
            // Main Settings List
            SettingsItem(
                icon = Icons.Default.Psychology,
                title = "AI Providers",
                subtitle = "Configure models & keys",
                onClick = { showAIConfigSheet = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                icon = Icons.Default.CloudSync,
                title = "Backup & Sync",
                subtitle = if (lastBackupTime > 0) {
                    val sdf = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
                    "Last: ${sdf.format(java.util.Date(lastBackupTime))}"
                } else "Not backed up",
                onClick = { showBackupSheet = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                icon = Icons.Default.Archive,
                title = "Archive",
                subtitle = "View archived notes",
                onClick = { showArchiveSheet = true }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Security & Storage
            if (isPinConfigured) {
                SettingsItem(
                    icon = Icons.Default.Password,
                    title = "Change PIN",
                    subtitle = "Update your security PIN",
                    onClick = { showPinChangeSheet = true }
                )

                Spacer(modifier = Modifier.height(12.dp))

                SettingsItem(
                    icon = Icons.Default.LockOpen,
                    title = "Remove PIN",
                    subtitle = "Disable PIN protection",
                    onClick = { showRemovePinDialog = true },
                    isDestructive = true,
                    iconColor = SafetyOrange
                )
            } else {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    title = "Set Up PIN",
                    subtitle = "Protect your notes",
                    onClick = { showPinSetupSheet = true }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsItem(
                icon = Icons.Default.CleaningServices,
                title = "Clear Cache",
                subtitle = formatCacheSize(cacheSizeBytes),
                onClick = onClearCache,
                isLoading = isClearingCache,
                enabled = !isClearingCache && cacheSizeBytes > 0,
                iconColor = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(12.dp))

            // About Section
            SettingsItem(
                icon = Icons.Default.Info,
                title = "About Cogni",
                subtitle = "Version 1.0.0",
                onClick = {},
                showArrow = false
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Footer
            Text(
                text = "Made with intelligence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // AI Configuration Bottom Sheet
    if (showAIConfigSheet) {
        AIConfigBottomSheet(
            sheetState = aiConfigSheetState,
            providerConfigs = providerConfigs,
            onDismiss = { showAIConfigSheet = false },
            onAddApiKey = onAddApiKey,
            onRemoveApiKey = onRemoveApiKey,
            onUpdateApiKey = onUpdateApiKey,
            onSetProviderEnabled = onSetProviderEnabled,
            onSetSelectedModel = onSetSelectedModel,
            onTestApiKey = onTestApiKey
        )
    }

    // Archive Bottom Sheet
    if (showArchiveSheet) {
        ModalBottomSheet(
            onDismissRequest = { showArchiveSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f) // Restrict height
                    .fillMaxWidth()
                    .padding(bottom = 32.dp) // Nav bar padding
            ) {
                archiveContent { showArchiveSheet = false }
            }
        }
    }

    // Backup Bottom Sheet
    if (showBackupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBackupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                 Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
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

    // Pin Setup Bottom Sheet
    if (showPinSetupSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPinSetupSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            HideSystemBars()
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                pinSetupContent { showPinSetupSheet = false }
            }
        }
    }

    // Pin Change Bottom Sheet
    if (showPinChangeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPinChangeSheet = false },
            sheetState = subSettingSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = LocalShapes.current.bottomSheet,
            dragHandle = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    )
                }
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp)
            ) {
                pinChangeContent { showPinChangeSheet = false }
            }
        }
    }

    // Remove PIN Dialog
    if (showRemovePinDialog) {
        AlertDialog(
            onDismissRequest = { showRemovePinDialog = false },
            title = { Text("Remove PIN?") },
            text = { Text("Anyone with access to your device will be able to view your notes.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemovePin()
                    showRemovePinDialog = false
                }) {
                    Text("Remove", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemovePinDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = LocalShapes.current.cardMedium
        )
    }
}



// Redundant SettingsGroup removed

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = LocalAccentColor.current,
    isDestructive: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    showArrow: Boolean = true
) {
    // Matching CategoryCard style
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                 modifier = Modifier
                     .size(40.dp)
                     .clip(CircleShape)
                     .background(iconColor.copy(alpha = 0.15f)),
                 contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = if (isDestructive) SafetyOrange else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Action
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = iconColor
                )
            } else if (showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIConfigBottomSheet(
    sheetState: SheetState,
    providerConfigs: Map<AIProvider, AIProviderConfig>,
    onDismiss: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit
) {
    val shapes = LocalShapes.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.5f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = "AI Providers",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                text = "Configure API keys for AI providers. The app will automatically fallback between providers if one fails.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val providerInfo = mapOf(
                AIProvider.GEMINI to Triple("Gemini", "Google's fastest AI", "https://aistudio.google.com/apikey"),
                AIProvider.DEEPSEEK to Triple("DeepSeek", "Cost-effective", "https://platform.deepseek.com"),
                AIProvider.GROQ to Triple("Groq", "Ultra-fast", "https://console.groq.com"),
                AIProvider.OPENAI to Triple("OpenAI", "GPT-4o", "https://platform.openai.com/api-keys"),
                AIProvider.OPENROUTER to Triple("OpenRouter", "Multi-model", "https://openrouter.ai/keys"),
                AIProvider.HUGGINGFACE to Triple("HuggingFace", "Open source", "https://huggingface.co/settings/tokens")
            )

            AIProvider.entries.forEachIndexed { index, provider ->
                val (name, description, _) = providerInfo[provider] ?: Triple("Unknown", "", "")
                val config = providerConfigs[provider]

                ProviderSection(
                    provider = provider,
                    providerName = name,
                    providerDescription = description,
                    apiKeys = config?.apiKeys ?: emptyList(),
                    isEnabled = config?.isEnabled ?: true,
                    selectedModel = config?.selectedModel ?: AIModels.getDefaultModel(provider),
                    availableModels = AIModels.getModelsForProvider(provider),
                    onAddKey = { onAddApiKey(provider, it) },
                    onRemoveKey = { onRemoveApiKey(provider, it) },
                    onUpdateKey = { old, new -> onUpdateApiKey(provider, old, new) },
                    onToggleEnabled = { onSetProviderEnabled(provider, it) },
                    onSelectModel = { onSetSelectedModel(provider, it) },
                    onTestKey = { key, callback -> onTestApiKey(provider, key, callback) }
                )

                if (index < AIProvider.entries.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status
            val hasAnyKeys = providerConfigs.values.any { it.apiKeys.isNotEmpty() }
            val configuredCount = providerConfigs.values.count { it.apiKeys.isNotEmpty() }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = if (hasAnyKeys) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasAnyKeys) "$configuredCount provider${if (configuredCount > 1) "s" else ""} configured" else "No API keys - using demo mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasAnyKeys) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }
}
