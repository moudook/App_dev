package com.example.smarty.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun TimeEditor(
    modifier: Modifier = Modifier,
    onSave: (hours: Int, minutes: Int, title: String) -> Unit = { _, _, _ -> }
) {
    var isEditing by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf(TextFieldValue("2")) }
    var minutes by remember { mutableStateOf(TextFieldValue("30")) }
    var title by remember { mutableStateOf(TextFieldValue("Event Title")) }

    val hoursFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isEditing) {
        if (isEditing) {
            delay(50) // Small delay to allow layout to settle
            hours = hours.copy(selection = TextRange(0, hours.text.length))
            hoursFocusRequester.requestFocus()
        }
    }

    fun handleToggle() {
        if (isEditing) {
            if (hours.text.isEmpty()) hours = hours.copy(text = "0")
            
            var newMins = minutes.text
            if (newMins.isEmpty()) {
                newMins = "00"
            } else if (newMins.length == 1) {
                newMins = "0$newMins"
            }
            minutes = minutes.copy(text = newMins)
            
            if (title.text.trim().isEmpty()) {
                title = title.copy(text = "Event Title")
            }
            
            onSave(
                hours.text.toIntOrNull() ?: 0,
                minutes.text.toIntOrNull() ?: 0,
                title.text
            )
        }
        isEditing = !isEditing
    }

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    val containerColor by animateColorAsState(
        targetValue = if (isEditing) Color.Transparent else surfaceVariant.copy(alpha = 0.4f),
        animationSpec = tween(300),
        label = "containerColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top Row: Time & Button
        Row(
            modifier = Modifier
                .clip(if (isEditing) RoundedCornerShape(0.dp) else CircleShape)
                .background(containerColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isEditing,
                    onClick = { handleToggle() }
                )
                .padding(if (isEditing) PaddingValues(0.dp) else PaddingValues(horizontal = 20.dp, vertical = 10.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Hours Section
            TimeSection(
                value = hours,
                onValueChange = { 
                    val filtered = it.text.filter { char -> char.isDigit() }.take(2)
                    hours = it.copy(text = filtered, selection = it.selection)
                },
                label = "Hr.",
                isEditing = isEditing,
                focusRequester = hoursFocusRequester
            )

            // Separator
            AnimatedVisibility(visible = !isEditing) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .height(18.dp)
                        .width(1.dp)
                        .background(onSurfaceVariant.copy(alpha = 0.3f), CircleShape)
                )
            }
            
            if (isEditing) {
                Spacer(modifier = Modifier.width(8.dp))
            }

            // Minutes Section
            TimeSection(
                value = minutes,
                onValueChange = {
                    val filtered = it.text.filter { char -> char.isDigit() }.take(2)
                    if (filtered.isNotEmpty() && (filtered.toIntOrNull() ?: 0) > 59) {
                        minutes = it.copy(text = "59", selection = it.selection)
                    } else {
                        minutes = it.copy(text = filtered, selection = it.selection)
                    }
                },
                label = "Min.",
                isEditing = isEditing
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Action Button
            Box(
                modifier = Modifier
                    .size(if (isEditing) 44.dp else 28.dp)
                    .clip(CircleShape)
                    .background(if (isEditing) primary else Color.Transparent)
                    .clickable { handleToggle() },
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = isEditing, label = "IconTransition") { editing ->
                    if (editing) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save",
                            tint = onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Bottom Row: Title Input
        AnimatedVisibility(visible = isEditing) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                textStyle = TextStyle(
                    color = onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                ),
                singleLine = true,
                cursorBrush = SolidColor(primary),
                modifier = Modifier
                    .widthIn(min = 220.dp)
                    .height(48.dp)
                    .background(surfaceVariant.copy(alpha = 0.3f), CircleShape)
                    .padding(horizontal = 20.dp),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.Center) {
                        if (title.text.isEmpty()) {
                            Text(
                                text = "Event Title",
                                color = onSurfaceVariant.copy(alpha = 0.5f),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun TimeSection(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    isEditing: Boolean,
    focusRequester: FocusRequester? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface

    val bgColor by animateColorAsState(
        targetValue = if (isEditing) {
            if (isFocused) surface else surfaceVariant.copy(alpha = 0.5f)
        } else Color.Transparent,
        animationSpec = tween(300),
        label = "bgColor"
    )

    val borderColor by animateColorAsState(
        targetValue = if (isEditing && isFocused) primary else Color.Transparent,
        animationSpec = tween(300),
        label = "borderColor"
    )

    val fontSize by animateFloatAsState(targetValue = if (isEditing) 24f else 20f, label = "fontSize")
    val labelSize by animateFloatAsState(targetValue = if (isEditing) 12f else 16f, label = "labelSize")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .then(if (isEditing) Modifier.height(48.dp).widthIn(min = 80.dp) else Modifier)
            .clip(CircleShape)
            .background(bgColor)
            .border(if (isEditing && isFocused) 2.dp else 0.dp, borderColor, CircleShape)
            .padding(if (isEditing) PaddingValues(horizontal = 16.dp) else PaddingValues(0.dp))
    ) {
        val modifier = if (focusRequester != null) {
            Modifier.focusRequester(focusRequester)
        } else Modifier

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = isEditing,
            textStyle = TextStyle(
                color = onSurface,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                textAlign = if (isEditing) TextAlign.Center else TextAlign.End
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            cursorBrush = SolidColor(primary),
            modifier = modifier
                .onFocusChanged { isFocused = it.isFocused }
                .then(if (isEditing) Modifier.width(36.dp) else Modifier.width(28.dp))
        )
        
        Spacer(modifier = Modifier.width(4.dp))
        
        Text(
            text = if (isEditing) label.uppercase() else label,
            fontSize = labelSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}
