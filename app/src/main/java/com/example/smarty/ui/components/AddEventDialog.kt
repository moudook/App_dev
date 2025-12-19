package com.example.smarty.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple dialog for adding a new calendar event.
 * Basic implementation - will be enhanced later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEventDialog(
    onDismiss: () -> Unit,
    onConfirm: (title: String, description: String?, startTime: Long, endTime: Long, isAllDay: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isAllDay by remember { mutableStateOf(false) }

    // Default to today at current hour, 1 hour duration
    val calendar = remember { Calendar.getInstance() }
    var startHour by remember { mutableIntStateOf(calendar.get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf((calendar.get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var endMinute by remember { mutableIntStateOf(0) }

    // Simple date display
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val currentDate = remember { dateFormat.format(calendar.time) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Event") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Event title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )

                // Date display (read-only for now - events are for today)
                Text(
                    text = "Date: $currentDate",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // All day toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("All day event")
                    Switch(
                        checked = isAllDay,
                        onCheckedChange = { isAllDay = it }
                    )
                }

                // Time selection (only if not all day)
                if (!isAllDay) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Start time
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Start",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Hour picker (simplified)
                                OutlinedTextField(
                                    value = startHour.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { h ->
                                            if (h in 0..23) startHour = h
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Text(":", modifier = Modifier.padding(top = 16.dp))
                                OutlinedTextField(
                                    value = startMinute.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { m ->
                                            if (m in 0..59) startMinute = m
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }

                        // End time
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "End",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(
                                    value = endHour.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { h ->
                                            if (h in 0..23) endHour = h
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Text(":", modifier = Modifier.padding(top = 16.dp))
                                OutlinedTextField(
                                    value = endMinute.toString().padStart(2, '0'),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { m ->
                                            if (m in 0..59) endMinute = m
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank()) {
                        val startCal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, if (isAllDay) 0 else startHour)
                            set(Calendar.MINUTE, if (isAllDay) 0 else startMinute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        val endCal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, if (isAllDay) 23 else endHour)
                            set(Calendar.MINUTE, if (isAllDay) 59 else endMinute)
                            set(Calendar.SECOND, if (isAllDay) 59 else 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onConfirm(
                            title,
                            description.ifBlank { null },
                            startCal.timeInMillis,
                            endCal.timeInMillis,
                            isAllDay
                        )
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
