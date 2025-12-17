package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.TodoItem
import com.example.smarty.data.model.getTodos
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.MonoFont
import java.util.UUID

/**
 * Bottom sheet for managing todos associated with a note.
 * Shows original note content and allows adding/managing todo items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTodoSheet(
    note: Note,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSaveTodos: (List<TodoItem>) -> Unit
) {
    var todos by remember(note.id) { mutableStateOf(note.getTodos()) }
    var newTodoText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Surface(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(2.dp)
            ) {
                Box(modifier = Modifier.size(width = 32.dp, height = 4.dp))
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "todos_",
                    style = MaterialTheme.typography.headlineSmall,
                    color = LocalAccentColor.current
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Original note content section
            Text(
                text = "Original Note",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        RoundedCornerShape(12.dp)
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            ) {
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Todo list section
            Text(
                text = "Tasks (${todos.count { it.isCompleted }}/${todos.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Todo items list
            if (todos.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(todos, key = { it.id }) { todo ->
                        TodoItemRow(
                            todo = todo,
                            onToggle = {
                                todos = todos.map {
                                    if (it.id == todo.id) it.copy(isCompleted = !it.isCompleted)
                                    else it
                                }
                            },
                            onDelete = {
                                todos = todos.filter { it.id != todo.id }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Add new todo input
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (newTodoText.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(12.dp)
                    ),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = if (newTodoText.isNotEmpty()) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(20.dp)
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        BasicTextField(
                            value = newTodoText,
                            onValueChange = { newTodoText = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MonoFont,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(LocalAccentColor.current),
                            singleLine = true
                        )
                        if (newTodoText.isEmpty()) {
                            Text(
                                text = "Add task or mention anything...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }

                    // Add button
                    AnimatedVisibility(
                        visible = newTodoText.isNotBlank(),
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        IconButton(
                            onClick = {
                                if (newTodoText.isNotBlank()) {
                                    // Parse input - could be multiple items separated by newlines or commas
                                    val newItems = parseNewTodos(newTodoText)
                                    todos = todos + newItems
                                    newTodoText = ""
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Add",
                                tint = LocalAccentColor.current
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tip: AI will help organize your tasks",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onSaveTodos(todos) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(ComponentSpacing.buttonCornerRadius),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save")
                }
            }
        }
    }
}

/**
 * Individual todo item row with checkbox, text, and delete button
 */
@Composable
private fun TodoItemRow(
    todo: TodoItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle() },
        color = if (todo.isCompleted)
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = todo.isCompleted,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = LocalAccentColor.current,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Text(
                text = todo.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (todo.isCompleted)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
                textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else null,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Parse input text into todo items.
 * Supports multiple items separated by newlines, commas, or bullet points.
 */
private fun parseNewTodos(input: String): List<TodoItem> {
    // Split by newlines, commas, or common bullet characters
    val items = input
        .split(Regex("[\\n,;]|[-•*]\\s"))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    return items.map { text ->
        TodoItem(
            id = UUID.randomUUID().toString(),
            text = text,
            isCompleted = false
        )
    }
}
