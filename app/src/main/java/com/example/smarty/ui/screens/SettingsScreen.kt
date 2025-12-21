package com.example.smarty.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.smarty.data.local.AIModels
import com.example.smarty.data.local.AIProvider
import com.example.smarty.data.local.AIProviderConfig
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.screens.settings.ProviderSection
import com.example.smarty.ui.screens.settings.maskApiKey
import com.example.smarty.util.api.KeyUsageStats
import com.example.smarty.ui.components.ShakeSensitivityControl
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
    providerPriorityOrder: List<AIProvider>,
    isPinConfigured: Boolean,
    isDarkTheme: Boolean,
    onBackClick: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    onRemovePin: () -> Unit,
    onToggleTheme: (Boolean) -> Unit,
    // Tavily Web Search API
    tavilyApiKey: String? = null,
    onSetTavilyApiKey: (String?) -> Unit = {},
    // Embedded Content Slots
    archiveContent: @Composable (() -> Unit) -> Unit,
    backupContent: @Composable (() -> Unit) -> Unit,
    pinSetupContent: @Composable (() -> Unit) -> Unit,
    pinChangeContent: @Composable (() -> Unit) -> Unit,
    lastBackupTime: Long = 0L,
    cacheSizeBytes: Long = 0L,
    onClearCache: () -> Unit = {},
    isClearingCache: Boolean = false,
    // Shake sensitivity
    shakeSensitivity: Float = 0.5f,
    onShakeSensitivityChange: (Float) -> Unit = {},
    // GROQ key usage stats
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    modifier: Modifier = Modifier,
    onRefreshModels: (AIProvider) -> Unit = {},
    getAvailableModels: (AIProvider) -> List<Pair<String, String>> = { AIModels.getModelsForProvider(it) }
) {
    var showRemovePinDialog by remember { mutableStateOf(false) }
    var showAIConfigSheet by remember { mutableStateOf(false) }
    val aiConfigSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Bottom Sheets for Sub-settings
    var showArchiveSheet by remember { mutableStateOf(false) }
    var showBackupSheet by remember { mutableStateOf(false) }
    var showPinSetupSheet by remember { mutableStateOf(false) }
    var showPinChangeSheet by remember { mutableStateOf(false) }
    var showAboutSheet by remember { mutableStateOf(false) }  // Newly added state
    var showShakeSensitivitySheet by remember { mutableStateOf(false) }
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

            // Shake Sensitivity
            SettingsItem(
                icon = Icons.Default.Vibration,
                title = "Shake Sensitivity",
                subtitle = "${(shakeSensitivity * 100).toInt()}% - ${
                    when {
                        shakeSensitivity < 0.3f -> "Low"
                        shakeSensitivity < 0.7f -> "Medium"
                        else -> "High"
                    }
                }",
                onClick = { showShakeSensitivitySheet = true },
                showArrow = true
            )

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
                subtitle = "Version 1.0.3",
                onClick = { showAboutSheet = true },
                showArrow = true
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
            providerPriorityOrder = providerPriorityOrder,
            onDismiss = { showAIConfigSheet = false },
            onAddApiKey = onAddApiKey,
            onRemoveApiKey = onRemoveApiKey,
            onUpdateApiKey = onUpdateApiKey,
            onSetProviderEnabled = onSetProviderEnabled,
            onSetSelectedModel = onSetSelectedModel,
            onSetProviderPriority = onSetProviderPriority,
            onTestApiKey = onTestApiKey,
            tavilyApiKey = tavilyApiKey,
            onSetTavilyApiKey = onSetTavilyApiKey,
            groqKeyUsageStats = groqKeyUsageStats,
            onRefreshModels = onRefreshModels,
            getAvailableModels = getAvailableModels
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

    // About Bottom Sheet
    if (showAboutSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAboutSheet = false },
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
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.85f)
                    .fillMaxWidth()
            ) {
                // Fixed Header
                Text(
                    text = "About Cogni",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 16.dp)
                )

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text =  "hello I am moudook,\n\n" +
                                "Initially, I tried to make Cogni a note-taking app for myself. That is why I added AI to it, so I do not get fussed with my notes and do not need to find them manually. I tried to add images, videos, documents like PDF and DOCX, links, and audio. I also tried to import an audio player and added more stuff.\n\n" +
                                "Then I tried using this app properly. To be honest, it did not even come close to my expectations. The AI could not even handle notes properly. So I played with some system instructions and tried the app again and again, but the same issue still persisted.\n\n" +
                                "After that, I tried to make the AI agentic and fully local, without any server. I am still working on it. When it is completed, it can refer to multiple note blocks, but never the private ones. Those are secure from AI in all ways possible. It can create new blocks, delete them, set up new to-dos for specific note blocks, and show citations so I know exactly where it is saying things from.\n\n" +
                                "I am also trying to add a calendar option so users can manage their time more efficiently and try to reduce screen time. The calendar can be accessed by the user and the AI, and both can add things based on it. I added a Dynamic Island style UI. I copied the idea from Apple, and I give them credit for bringing this thing to life. It reacts to the current page, shows the time remaining for the next task or meeting, shows the number of items on the current page, and shows when the AI agent is working.\n\n" +
                                "I also tried my best to keep the UI persistent. The startup animation has a cloud feel, not a solid bounding feel. The main page has a living orb animation. The chat page has an animation for personality, and when you shake the screen, the animation shifts to a more focused animation that stays on the chat page.\n\n" +
                                "I also tried to give a shimmering effect with halftone in it on the input block of the chat page, so the user knows when the speech-to-text is working. One more thing is that I personally feel popups are irritating. That is why I added slide-in-from-bottom animations, so I can work around popups while taking minimal space.\n\n" +
                                "I use this approach in the chat history section, the share-from-other-apps option, the settings, the to-do section, and the music player. I also tried to make a beautiful visualization for the music player so that it feels alive.\n\n" +
                                "I searched for advanced methods and formulas to make the app run optimally on edge devices. I also used mathematical theorems to improve its efficiency.\n\n" +
                                "I also added an in-app API section so users can set up API keys no matter the provider. I am planning to secure this section with a strong password so no other person can access it. I added speech recognition using Google’s API inside the app, added a search option, added chat history for the AI, and also added a shake-to-private-mode feature so users can be sure that AI can never access those blocks in any way possible.\n\n" +
                                "The app also provides AI categorization based on text and images. I am working on document parsing on the device so documents can also be included in categorization. I added a local on-device layer to prevent prompt injection by sanitizing content across multiple languages. I do not use emojis, so I do not need them in this app.\n\n" +
                                "I respect user data, so the app runs completely on the device, with only API calls to the AI and speech recognition APIs. The app also provides an option to create a backup ZIP that contains everything the user has contributed to this app.\n\n" +
                                "I am still working on the UI and the agentic part, so please mention any issues you find using GitHub.\n\n" +
                                "I will keep improving it and try to make it your app.\n" +
                                "Thank you.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp)
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }

    // Shake Sensitivity Bottom Sheet
    if (showShakeSensitivitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showShakeSensitivitySheet = false },
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Shake Sensitivity",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Text(
                    text = "Adjust how sensitive the shake gesture is for toggling private mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                // Semicircle sensitivity control
                ShakeSensitivityControl(
                    sensitivity = shakeSensitivity,
                    onSensitivityChange = onShakeSensitivityChange,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Hint text
                Text(
                    text = when {
                        shakeSensitivity < 0.3f -> "Low: Requires strong shake movement"
                        shakeSensitivity < 0.7f -> "Medium: Balanced sensitivity"
                        else -> "High: Light shake triggers mode switch"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
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
    providerPriorityOrder: List<AIProvider>,
    onDismiss: () -> Unit,
    onAddApiKey: (AIProvider, String) -> Unit,
    onRemoveApiKey: (AIProvider, String) -> Unit,
    onUpdateApiKey: (AIProvider, String, String) -> Unit,
    onSetProviderEnabled: (AIProvider, Boolean) -> Unit,
    onSetSelectedModel: (AIProvider, String) -> Unit,
    onSetProviderPriority: (List<AIProvider>) -> Unit,
    onTestApiKey: (AIProvider, String, (Boolean) -> Unit) -> Unit,
    tavilyApiKey: String? = null,
    onSetTavilyApiKey: (String?) -> Unit = {},
    groqKeyUsageStats: List<KeyUsageStats> = emptyList(),
    onRefreshModels: (AIProvider) -> Unit,
    getAvailableModels: (AIProvider) -> List<Pair<String, String>>
) {
    val shapes = LocalShapes.current

    // Local state for drag-and-drop reordering
    var localProviderOrder by remember { mutableStateOf(providerPriorityOrder) }
    var draggedItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    // Sync with external state when it changes
    LaunchedEffect(providerPriorityOrder) {
        localProviderOrder = providerPriorityOrder
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        HideSystemBars()
        Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
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
                text = "Hold and drag providers to reorder fallback priority.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val providerInfo = mapOf(
                AIProvider.GEMINI to Triple("Gemini", "Google's fastest AI", "https://aistudio.google.com/apikey"),
                AIProvider.DEEPSEEK to Triple("DeepSeek", "Cost-effective", "https://platform.deepseek.com"),
                AIProvider.GROQ to Triple("Groq", "Ultra-fast", "https://console.groq.com"),
                AIProvider.CEREBRAS to Triple("Cerebras", "2000+ tokens/sec", "https://cloud.cerebras.ai"),
                AIProvider.COHERE to Triple("Cohere", "Command models", "https://dashboard.cohere.com/api-keys"),
                AIProvider.OPENAI to Triple("OpenAI", "GPT-4o", "https://platform.openai.com/api-keys"),
                AIProvider.ANTHROPIC to Triple("Anthropic", "Claude models", "https://console.anthropic.com/settings/keys"),
                AIProvider.OPENROUTER to Triple("OpenRouter", "Multi-model", "https://openrouter.ai/keys"),
                AIProvider.HUGGINGFACE to Triple("HuggingFace", "Open source", "https://huggingface.co/settings/tokens")
            )

            // Iterate through providers with drag-and-drop reordering
            localProviderOrder.forEachIndexed { index, provider ->
                val (name, description, _) = providerInfo[provider] ?: Triple("Unknown", "", "")
                val config = providerConfigs[provider]
                val isDragging = draggedItemIndex == index

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            if (isDragging) {
                                translationY = dragOffsetY
                                scaleX = 1.02f
                                scaleY = 1.02f
                            }
                        }
                        .zIndex(if (isDragging) 1f else 0f)
                        .then(
                            if (isDragging) Modifier.shadow(8.dp, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .background(
                            if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                            else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    // Drag Handle with gesture detection
                    Box(
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggedItemIndex = index
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        // Calculate target position based on drag offset
                                        val itemHeight = 120f // Approximate item height in pixels
                                        val moveBy = (dragOffsetY / itemHeight).toInt()
                                        val targetIndex = (index + moveBy).coerceIn(0, localProviderOrder.size - 1)

                                        if (targetIndex != index) {
                                            val newList = localProviderOrder.toMutableList()
                                            val item = newList.removeAt(index)
                                            newList.add(targetIndex, item)
                                            localProviderOrder = newList
                                            onSetProviderPriority(newList)
                                        }

                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggedItemIndex = null
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Drag indicator icon
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = if (isDragging) LocalAccentColor.current
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp)
                            )
                            // Priority number badge
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isDragging) LocalAccentColor.current
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        // Build usage stats map for GROQ keys
                        val keyUsageStatsMap = if (provider == AIProvider.GROQ) {
                            groqKeyUsageStats.associateBy { it.key }
                        } else {
                            emptyMap()
                        }

                        ProviderSection(
                            provider = provider,
                            providerName = name,
                            providerDescription = description,
                            apiKeys = config?.apiKeys ?: emptyList(),
                            isEnabled = config?.isEnabled ?: true,
                            selectedModel = config?.selectedModel ?: AIModels.getDefaultModel(provider),
                            availableModels = getAvailableModels(provider),
                            onAddKey = { onAddApiKey(provider, it) },
                            onRemoveKey = { onRemoveApiKey(provider, it) },
                            onUpdateKey = { old, new -> onUpdateApiKey(provider, old, new) },
                            onToggleEnabled = { onSetProviderEnabled(provider, it) },
                            onSelectModel = { onSetSelectedModel(provider, it) },
                            onTestKey = { key, callback -> onTestApiKey(provider, key, callback) },
                            keyUsageStats = keyUsageStatsMap,
                            onRefreshModels = if (provider == AIProvider.GROQ) { { onRefreshModels(provider) } } else null
                        )
                    }
                }

                if (index < localProviderOrder.size - 1) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tavily Web Search Section
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )

            TavilyApiSection(
                apiKey = tavilyApiKey,
                onSetApiKey = onSetTavilyApiKey
            )

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

/**
 * Tavily Web Search API configuration section.
 * Separate from AI providers as it's a search tool, not a chat model.
 */
@Composable
private fun TavilyApiSection(
    apiKey: String?,
    onSetApiKey: (String?) -> Unit
) {
    var showKeyInput by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp)
                )

                Column {
                    Text(
                        text = "Tavily Web Search",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (apiKey != null) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Enable AI web search capabilities",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Status indicator
            if (apiKey != null) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Configured",
                    tint = LocalAccentColor.current,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Expanded content
        androidx.compose.animation.AnimatedVisibility(
            visible = isExpanded,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Info text
                Text(
                    text = "Tavily provides real-time web search for AI. Get your free API key (1,000 requests/month) from tavily.com",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )

                if (apiKey != null) {
                    // Show existing key
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
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (showKey) apiKey else maskApiKey(apiKey),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = com.example.smarty.ui.theme.MonoFont
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )

                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (showKey) "Hide" else "Show",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(onClick = { onSetApiKey(null) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove",
                                    tint = SafetyOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                } else if (showKeyInput) {
                    // Input for new key
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = keyInput,
                                        onValueChange = { keyInput = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = com.example.smarty.ui.theme.MonoFont,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccentColor.current),
                                        singleLine = true
                                    )
                                    if (keyInput.isEmpty()) {
                                        Text(
                                            text = "tvly-XXXXX...",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = com.example.smarty.ui.theme.MonoFont
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = {
                                    showKeyInput = false
                                    keyInput = ""
                                }) {
                                    Text("Cancel")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (keyInput.isNotBlank()) {
                                            onSetApiKey(keyInput.trim())
                                            keyInput = ""
                                            showKeyInput = false
                                        }
                                    },
                                    enabled = keyInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = LocalAccentColor.current,
                                        contentColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Text("Save")
                                }
                            }
                        }
                    }
                } else {
                    // Add key button
                    OutlinedButton(
                        onClick = { showKeyInput = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(ComponentSpacing.iconSize)
                        )
                        Spacer(modifier = Modifier.width(ComponentSpacing.iconGap))
                        Text("Add Tavily API Key")
                    }
                }
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
