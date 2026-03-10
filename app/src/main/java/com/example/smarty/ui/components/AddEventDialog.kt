package com.example.smarty.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.smarty.R
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
    onConfirm: (title: String, description: String?, startTime: Long, endTime: Long, isAllDay: Boolean) -> Unit,
    initialDate: Calendar = Calendar.getInstance()
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isAllDay by remember { mutableStateOf(false) }

    // Use the provided initial date (selected date from calendar)
    val selectedDate = remember { initialDate.clone() as Calendar }
    var startHour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var startMinute by remember { mutableIntStateOf(0) }
    var endHour by remember { mutableIntStateOf((Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24) }
    var endMinute by remember { mutableIntStateOf(0) }

    // Display the selected date
    val dateFormat = remember { SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()) }
    val displayDate = remember(selectedDate) { dateFormat.format(selectedDate.time) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.new_event),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Custom content
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
                    label = { Text(stringResource(R.string.event_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Description
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 3
                )

                // Date display (shows the selected date from calendar)
                Text(
                    text = stringResource(R.string.date_label, displayDate.lowercase()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // All day toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.all_day_event))
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
                                text = stringResource(R.string.start),
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
                                Text(stringResource(R.string.colon_separator), modifier = Modifier.padding(top = 16.dp))
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
                                text = stringResource(R.string.end),
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
                                Text(stringResource(R.string.colon_separator), modifier = Modifier.padding(top = 16.dp))
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

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.cancel))
                }

                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            val startCal = (selectedDate.clone() as Calendar).apply {
                                set(Calendar.HOUR_OF_DAY, if (isAllDay) 0 else startHour)
                                set(Calendar.MINUTE, if (isAllDay) 0 else startMinute)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            val endCal = (selectedDate.clone() as Calendar).apply {
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
                    enabled = title.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(stringResource(R.string.add))
                }
            }
        }
    }
}
}
