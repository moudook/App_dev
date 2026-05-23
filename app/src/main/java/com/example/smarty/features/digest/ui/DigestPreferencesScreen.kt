package com.example.smarty.features.digest.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Digest Preferences Screen
 * Configure daily/weekly digest schedule and preferences
 * Reuses: EmptyStatePlaceholder, Card, Switch
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigestPreferencesScreen(
    preferences: DigestPreferences = DigestPreferences(),
    onSavePreferences: (DigestPreferences) -> Unit = {},
    onNavigateBack: () -> Unit = {},
) {
    var dailyTime by remember { mutableStateOf(preferences.dailyTime) }
    var weeklyDay by remember { mutableStateOf(preferences.weeklyDay) }
    var enableDaily by remember { mutableStateOf(preferences.enableDaily) }
    var enableWeekly by remember { mutableStateOf(preferences.enableWeekly) }
    var includeNotes by remember { mutableStateOf(preferences.includeNotes) }
    var includeTasks by remember { mutableStateOf(preferences.includeTasks) }
    var includeCalendar by remember { mutableStateOf(preferences.includeCalendar) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Digest Preferences") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            onSavePreferences(
                                DigestPreferences(
                                    dailyTime = dailyTime,
                                    weeklyDay = weeklyDay,
                                    enableDaily = enableDaily,
                                    enableWeekly = enableWeekly,
                                    includeNotes = includeNotes,
                                    includeTasks = includeTasks,
                                    includeCalendar = includeCalendar,
                                ),
                            )
                        },
                    ) {
                        Text("Save")
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Schedule Section
            Text(
                text = "Schedule",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Daily Digest
            DigestPreferenceCard(
                title = "Daily Digest",
                icon = Icons.Default.Today,
                enabled = enableDaily,
                onEnableChange = { enableDaily = it },
            ) {
                if (enableDaily) {
                    Spacer(Modifier.height(12.dp))
                    TimePickerRow(
                        hour = dailyTime / 100,
                        minute = dailyTime % 100,
                        onTimeChange = { hour, minute -> dailyTime = hour * 100 + minute },
                    )
                }
            }

            // Weekly Digest
            DigestPreferenceCard(
                title = "Weekly Digest",
                icon = Icons.Default.CalendarMonth,
                enabled = enableWeekly,
                onEnableChange = { enableWeekly = it },
            ) {
                if (enableWeekly) {
                    Spacer(Modifier.height(12.dp))
                    DayPickerRow(
                        selectedDay = weeklyDay,
                        onDaySelected = { weeklyDay = it },
                    )
                }
            }

            // Content Section
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Content",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Content Preferences
            DigestPreferenceCard(
                title = "Include Notes",
                icon = Icons.AutoMirrored.Filled.Note,
                enabled = includeNotes,
                onEnableChange = { includeNotes = it },
                showDivider = false,
            )

            DigestPreferenceCard(
                title = "Include Tasks",
                icon = Icons.Default.Task,
                enabled = includeTasks,
                onEnableChange = { includeTasks = it },
                showDivider = false,
            )

            DigestPreferenceCard(
                title = "Include Calendar",
                icon = Icons.Default.Event,
                enabled = includeCalendar,
                onEnableChange = { includeCalendar = it },
                showDivider = false,
            )

            // Preview Section
            Spacer(Modifier.height(8.dp))
            DigestPreviewCard()
        }
    }
}

@Composable
private fun DigestPreferenceCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onEnableChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnableChange,
                )
            }
            content()
        }
    }
    if (showDivider) {
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TimePickerRow(
    hour: Int,
    minute: Int,
    onTimeChange: (Int, Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Time:", style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = {
                    val newHour = (hour - 1 + 24) % 24
                    onTimeChange(newHour, minute)
                },
                label = { Text("-") },
            )
            Text(
                text = String.format("%02d:%02d", hour, minute),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            AssistChip(
                onClick = {
                    val newHour = (hour + 1) % 24
                    onTimeChange(newHour, minute)
                },
                label = { Text("+") },
            )
        }
    }
}

@Composable
private fun DayPickerRow(
    selectedDay: String,
    onDaySelected: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        days.forEach { day ->
            FilterChip(
                selected = selectedDay == day,
                onClick = { onDaySelected(day) },
                label = { Text(day) },
            )
        }
    }
}

@Composable
private fun DigestPreviewCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your digest will include:\n• Summary of recent notes\n• Task progress updates\n• Upcoming calendar events",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

data class DigestPreferences(
    val dailyTime: Int = 700, // 7:00 AM
    val weeklyDay: String = "Sun",
    val enableDaily: Boolean = true,
    val enableWeekly: Boolean = true,
    val includeNotes: Boolean = true,
    val includeTasks: Boolean = true,
    val includeCalendar: Boolean = true,
)
